package sweetgirl.croissant.alarm.service;

import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.alarm.dto.request.CourseNotificationReadRequest;
import sweetgirl.croissant.alarm.dto.request.NoticeSendRequest;
import sweetgirl.croissant.alarm.dto.response.NoticeSendResponse;
import sweetgirl.croissant.alarm.dto.response.NotificationListResponse;
import sweetgirl.croissant.alarm.dto.response.NotificationResponse;
import sweetgirl.croissant.alarm.dto.response.UnreadCountResponse;
import sweetgirl.croissant.alarm.entity.Notification;
import sweetgirl.croissant.alarm.enums.NotificationTargetType;
import sweetgirl.croissant.alarm.enums.NotificationType;
import sweetgirl.croissant.alarm.repository.NotificationRepository;
import sweetgirl.croissant.course.entity.Course;
import sweetgirl.croissant.course.repository.CourseRepository;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.repository.MemberRepository;

/**
 * 알림의 적재·조회. 종류가 무엇이든 한 테이블에 쌓이고 한 목록으로 나간다.
 *
 * <p>구버전 앱을 위해 "저장 알림만" 보는 길이 따로 열려 있다. 스토어에 깔린 옛 앱은 응답에서
 * actorNickname·courseTitle을 꺼내 문장을 조립하는데, 공지에는 그 재료가 없어서 빈칸투성이
 * 문장이 뜬다. 그 앱들이 다 올라오면 by-type 경로를 지운다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotificationService {

	private static final int MAX_PAGE_SIZE = 50;

	private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

	private final NotificationRepository notificationRepository;
	private final CourseRepository courseRepository;
	private final MemberRepository memberRepository;

	// ── 조회 ────────────────────────────────────────────────────────────────

	public NotificationListResponse getMine(Long memberId, int page, int size) {
		Slice<Notification> slice = notificationRepository.findMine(memberId, pageable(page, size));
		return toListResponse(slice);
	}

	/** 구버전 앱 전용 — 저장 알림만. */
	public NotificationListResponse getMineByType(Long memberId, NotificationType type, int page, int size) {
		Slice<Notification> slice = notificationRepository
			.findMineByType(memberId, type, pageable(page, size));
		return toListResponse(slice);
	}

	public UnreadCountResponse getUnreadCount(Long memberId) {
		return new UnreadCountResponse(notificationRepository.countUnread(memberId));
	}

	public UnreadCountResponse getUnreadCountByType(Long memberId, NotificationType type) {
		return new UnreadCountResponse(
			notificationRepository.countUnreadByType(memberId, type));
	}

	// ── 읽음 ────────────────────────────────────────────────────────────────

	/**
	 * 읽음 처리. 남의 알림 id를 섞어 보내도 recipient로 걸러져 조용히 무시된다 —
	 * 남의 알림을 읽음 처리하려 한 것이 의미 있는 실패는 아니라서 예외로 만들지 않는다.
	 */
	@Transactional
	public void markAsRead(Long memberId, CourseNotificationReadRequest request) {
		LocalDateTime now = LocalDateTime.now();
		if (request == null || request.isAll()) {
			notificationRepository.markAllAsRead(memberId, now);
			return;
		}
		notificationRepository.findByRecipientIdAndIdIn(memberId, request.getNotificationIds())
			.forEach(notification -> notification.markAsRead(now));
	}

	/**
	 * 구버전 앱의 전체 읽음. 그 앱이 보지도 못하는 공지까지 읽음으로 만들면,
	 * 나중에 업데이트했을 때 못 본 공지가 이미 읽은 것으로 남는다.
	 */
	@Transactional
	public void markAsReadByType(Long memberId, NotificationType type,
			CourseNotificationReadRequest request) {
		LocalDateTime now = LocalDateTime.now();
		if (request == null || request.isAll()) {
			notificationRepository.markAllAsReadByType(memberId, type, now);
			return;
		}
		notificationRepository.findByRecipientIdAndIdIn(memberId, request.getNotificationIds()).stream()
			.filter(notification -> notification.getType() == type)
			.forEach(notification -> notification.markAsRead(now));
	}

	// ── 적재 ────────────────────────────────────────────────────────────────

	/**
	 * "누가 내 빵투어를 저장했다"를 쌓는다. 같은 사람이 같은 코스를 다시 저장했으면 새 줄을
	 * 만들지 않고 시각만 갱신해 맨 위로 올린다.
	 *
	 * <p>자기 코스 저장과 비공개 코스 저장은 {@code SavedCourseCommandService}가 이미 막으므로
	 * 여기서 다시 거르지 않는다. 다만 적재는 저장하기와 다른 트랜잭션이라, 그 사이 코스나 회원이
	 * 사라졌을 수 있어 조회 실패는 조용히 넘긴다 — 알림 하나 때문에 로그를 시끄럽게 할 일이 아니다.
	 */
	@Transactional
	public void recordCourseSaved(Long courseId, Long actorId, Long ownerId) {
		LocalDateTime now = LocalDateTime.now();

		Course course = courseRepository.findByIdAndDeletedAtIsNull(courseId).orElse(null);
		String title = course == null ? null : course.getTitle();

		notificationRepository.findByActorIdAndTypeAndTargetTypeAndTargetId(
			actorId, NotificationType.COURSE_SAVED, NotificationTargetType.COURSE, courseId)
			.ifPresentOrElse(
				existing -> existing.renew(now, title),
				() -> createCourseSaved(course, actorId, ownerId, now));
	}

	private void createCourseSaved(Course course, Long actorId, Long ownerId, LocalDateTime now) {
		Member actor = memberRepository.findByIdAndDeletedAtIsNull(actorId).orElse(null);
		Member owner = memberRepository.findByIdAndDeletedAtIsNull(ownerId).orElse(null);
		if (course == null || actor == null || owner == null) {
			log.info("빵투어 저장 알림 skip — 대상이 사라졌다 [actorId={}, ownerId={}]", actorId, ownerId);
			return;
		}
		notificationRepository.save(Notification.activity(
			owner, NotificationType.COURSE_SAVED, actor,
			NotificationTargetType.COURSE, course.getId(), course.getTitle(), now));
	}

	/**
	 * 전체 공지를 사람마다 한 줄씩 쌓는다.
	 *
	 * <p>공지 한 줄만 두고 조회 때 합치는 방법도 있지만, 그러면 페이징이 두 소스를 머지해야 해
	 * 목록 코드가 복잡해진다. 지금 회원 수라면 사람마다 한 줄이 훨씬 단순하고 충분히 싸다.
	 */
	@Transactional
	public NoticeSendResponse sendNotice(NoticeSendRequest request) {
		LocalDateTime now = LocalDateTime.now();
		int recipients = notificationRepository.insertNoticeForAllMembers(
			request.getTitle(), request.getBody(), request.getLink(), now);

		log.info("공지 발송 [title={}, recipients={}]", request.getTitle(), recipients);
		return new NoticeSendResponse(recipients);
	}

	private Pageable pageable(int page, int size) {
		return PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
	}

	private NotificationListResponse toListResponse(Slice<Notification> slice) {
		return new NotificationListResponse(
			slice.getContent().stream().map(NotificationResponse::from).toList(),
			slice.hasNext());
	}
}
