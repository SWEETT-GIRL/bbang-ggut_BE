package sweetgirl.croissant.course.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.alarm.entity.Notification;
import sweetgirl.croissant.alarm.enums.NotificationTargetType;
import sweetgirl.croissant.alarm.enums.NotificationType;
import sweetgirl.croissant.alarm.event.PushRequestedEvent;
import sweetgirl.croissant.alarm.repository.NotificationRepository;
import sweetgirl.croissant.alarm.service.NotificationRecorder;
import sweetgirl.croissant.course.entity.Course;
import sweetgirl.croissant.course.repository.CourseMemberRepository;
import sweetgirl.croissant.course.repository.CourseRepository;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.repository.MemberRepository;

/**
 * 빵투어 전날 저녁의 "내일 빵투어 가는 날" 푸시. 날짜를 정한 빵투어의 방장과 일행 모두에게 간다.
 * 서비스 알림이라 마케팅 동의를 보지 않고, 알림 수신을 켠 회원에게만 간다({@code PushDispatcher} 가 본다).
 *
 * <p>같은 발송이 두 번 나가지 않게 두 겹으로 막는다 — 인스턴스가 둘이어도 한쪽만 돌도록 잠그고,
 * 그날 이미 만든 알림이 있으면 건너뛴다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CourseReminderPushService {

	static final long LOCK_KEY = 7_260_201L;
	static final String TITLE = "내일 빵투어 가는 날";

	private final CourseRepository courseRepository;
	private final CourseMemberRepository courseMemberRepository;
	private final MemberRepository memberRepository;
	private final NotificationRepository notificationRepository;
	private final NotificationRecorder notificationRecorder;
	private final ApplicationEventPublisher eventPublisher;

	/**
	 * 내일 시작하는 빵투어마다 참여자에게 알린다. 전날 저녁에 부른다. 문구에 빵투어 이름이 들어가 빵투어별로 한 알림씩이다.
	 *
	 * @return 알린 사람 수(빵투어별 합). 오늘 이미 보냈거나 다른 인스턴스가 보내는 중이면 -1
	 */
	@Transactional
	public int sendTomorrowReminders(LocalDateTime now) {
		LocalDate today = now.toLocalDate();
		if (!courseRepository.tryAdvisoryXactLock(LOCK_KEY)
			|| notificationRepository.existsByTypeAndCreatedAtGreaterThanEqual(
				NotificationType.COURSE_TOMORROW, today.atStartOfDay())) {
			return -1;
		}
		int notified = 0;
		for (Course course : courseRepository.findAllByStartDateAndDeletedAtIsNull(today.plusDays(1))) {
			List<Member> participants = participantsOf(course);
			if (participants.isEmpty()) {
				continue;
			}
			Notification recorded = notificationRecorder.recordForMembers(Notification.announcement(
				NotificationType.COURSE_TOMORROW, TITLE, course.getTitle(), "/course/" + course.getId(),
				NotificationTargetType.COURSE, course.getId(), course.getTitle(), null), participants, now);
			participants.forEach(member -> eventPublisher.publishEvent(
				PushRequestedEvent.to(recorded.getId(), member.getId())));
			notified += participants.size();
		}
		log.info("[빵투어 전날 푸시] date={} recipients={}", today.plusDays(1), notified);
		return notified;
	}

	/** 방장과 일행(나간 사람 제외). 방장도 일행 행을 가질 수 있어 겹침을 지우고, 탈퇴한 회원은 뺀다. */
	private List<Member> participantsOf(Course course) {
		Set<Long> memberIds = new LinkedHashSet<>();
		memberIds.add(course.getMember().getId());
		courseMemberRepository.findAllByIdCourseIdAndDeletedAtIsNullOrderByJoinedAtAsc(course.getId())
			.forEach(courseMember -> memberIds.add(courseMember.getId().getMemberId()));
		return memberRepository.findAllById(memberIds).stream()
			.filter(member -> !member.isDeleted())
			.toList();
	}
}
