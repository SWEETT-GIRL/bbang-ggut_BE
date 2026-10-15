package sweetgirl.croissant.course.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import sweetgirl.croissant.alarm.entity.Notification;
import sweetgirl.croissant.alarm.enums.NotificationTargetType;
import sweetgirl.croissant.alarm.enums.NotificationType;
import sweetgirl.croissant.alarm.event.PushRequestedEvent;
import sweetgirl.croissant.alarm.repository.NotificationRepository;
import sweetgirl.croissant.alarm.service.NotificationRecorder;
import sweetgirl.croissant.course.entity.Course;
import sweetgirl.croissant.course.entity.CourseMember;
import sweetgirl.croissant.course.entity.CourseMemberId;
import sweetgirl.croissant.course.repository.CourseMemberRepository;
import sweetgirl.croissant.course.repository.CourseRepository;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.repository.MemberRepository;

@ExtendWith(MockitoExtension.class)
class CourseReminderPushServiceTest {

	/** 저녁 7 시. 내일은 10/15 다. */
	private static final LocalDateTime EVENING = LocalDateTime.of(2026, 10, 14, 19, 0);
	private static final LocalDate TOMORROW = LocalDate.of(2026, 10, 15);

	@Mock
	private CourseRepository courseRepository;
	@Mock
	private CourseMemberRepository courseMemberRepository;
	@Mock
	private MemberRepository memberRepository;
	@Mock
	private NotificationRepository notificationRepository;
	@Mock
	private NotificationRecorder notificationRecorder;
	@Mock
	private ApplicationEventPublisher eventPublisher;

	@InjectMocks
	private CourseReminderPushService courseReminderPushService;

	private Member member(Long id, boolean deleted) {
		Member member = mock(Member.class);
		// 탈퇴한 회원은 걸러져 id 를 읽지 않는다
		lenient().when(member.getId()).thenReturn(id);
		given(member.isDeleted()).willReturn(deleted);
		return member;
	}

	private Course course(Long id, String title, Member owner) {
		Course course = mock(Course.class);
		given(course.getId()).willReturn(id);
		given(course.getTitle()).willReturn(title);
		given(course.getMember()).willReturn(owner);
		return course;
	}

	private CourseMember courseMember(Long memberId) {
		CourseMemberId id = mock(CourseMemberId.class);
		given(id.getMemberId()).willReturn(memberId);
		CourseMember courseMember = mock(CourseMember.class);
		given(courseMember.getId()).willReturn(id);
		return courseMember;
	}

	private void givenFirstRunOfTheDay() {
		given(courseRepository.tryAdvisoryXactLock(CourseReminderPushService.LOCK_KEY)).willReturn(true);
		given(notificationRepository.existsByTypeAndCreatedAtGreaterThanEqual(
			NotificationType.COURSE_TOMORROW, EVENING.toLocalDate().atStartOfDay())).willReturn(false);
	}

	@Test
	@DisplayName("내일 시작하는 빵투어의 방장과 일행에게 빵투어 이름과 링크로 알린다 — 방장이 일행 행도 가지면 한 번만, 탈퇴한 일행은 뺀다")
	void remindsParticipants() {
		givenFirstRunOfTheDay();
		Member owner = member(1L, false);
		Member mate = member(2L, false);
		Member gone = member(3L, true);
		// 헬퍼가 다른 mock 을 스텁하므로 given(...) 안에서 부르면 UnfinishedStubbing 이 난다 — 먼저 만든다.
		List<Course> courses = List.of(course(812L, "성수 빵투어", owner));
		List<CourseMember> courseMembers = List.of(courseMember(1L), courseMember(2L), courseMember(3L));
		given(courseRepository.findAllByStartDateAndDeletedAtIsNull(TOMORROW)).willReturn(courses);
		given(courseMemberRepository.findAllByIdCourseIdAndDeletedAtIsNullOrderByJoinedAtAsc(812L))
			.willReturn(courseMembers);
		given(memberRepository.findAllById(any())).willReturn(List.of(owner, mate, gone));
		Notification recorded = mock(Notification.class);
		given(recorded.getId()).willReturn(900L);
		given(notificationRecorder.recordForMembers(any(Notification.class), anyList(), eq(EVENING)))
			.willReturn(recorded);

		assertThat(courseReminderPushService.sendTomorrowReminders(EVENING)).isEqualTo(2);

		ArgumentCaptor<Notification> notification = ArgumentCaptor.forClass(Notification.class);
		@SuppressWarnings("unchecked") ArgumentCaptor<List<Member>> members = ArgumentCaptor.forClass(List.class);
		then(notificationRecorder).should().recordForMembers(notification.capture(), members.capture(), eq(EVENING));
		assertThat(members.getValue()).containsExactly(owner, mate);
		assertThat(notification.getValue().getType()).isEqualTo(NotificationType.COURSE_TOMORROW);
		assertThat(notification.getValue().getTitle()).isEqualTo("내일 빵투어 가는 날");
		assertThat(notification.getValue().getBody()).isEqualTo("성수 빵투어");
		assertThat(notification.getValue().getDeepLink()).isEqualTo("/course/812");
		assertThat(notification.getValue().getTargetType()).isEqualTo(NotificationTargetType.COURSE);
		assertThat(notification.getValue().getTargetId()).isEqualTo(812L);
		then(eventPublisher).should(times(2)).publishEvent(any(PushRequestedEvent.class));
	}

	@Test
	@DisplayName("오늘 이미 보냈으면 다시 보내지 않는다")
	void skipsWhenAlreadySentToday() {
		given(courseRepository.tryAdvisoryXactLock(CourseReminderPushService.LOCK_KEY)).willReturn(true);
		given(notificationRepository.existsByTypeAndCreatedAtGreaterThanEqual(
			NotificationType.COURSE_TOMORROW, EVENING.toLocalDate().atStartOfDay())).willReturn(true);

		assertThat(courseReminderPushService.sendTomorrowReminders(EVENING)).isEqualTo(-1);

		then(courseRepository).should(org.mockito.Mockito.never()).findAllByStartDateAndDeletedAtIsNull(any());
	}

	@Test
	@DisplayName("다른 인스턴스가 보내는 중이면 물러선다")
	void skipsWhenLocked() {
		given(courseRepository.tryAdvisoryXactLock(CourseReminderPushService.LOCK_KEY)).willReturn(false);

		assertThat(courseReminderPushService.sendTomorrowReminders(EVENING)).isEqualTo(-1);
	}

	@Test
	@DisplayName("내일 가는 빵투어가 없으면 알림을 만들지 않는다")
	void nothingTomorrow() {
		givenFirstRunOfTheDay();
		given(courseRepository.findAllByStartDateAndDeletedAtIsNull(TOMORROW)).willReturn(List.of());

		assertThat(courseReminderPushService.sendTomorrowReminders(EVENING)).isZero();

		then(notificationRecorder).shouldHaveNoInteractions();
	}
}
