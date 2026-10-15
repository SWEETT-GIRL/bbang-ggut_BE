package sweetgirl.croissant.alarm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.SliceImpl;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.alarm.dto.request.CourseNotificationReadRequest;
import sweetgirl.croissant.alarm.dto.request.NoticeSendRequest;
import sweetgirl.croissant.alarm.dto.response.NoticeSendResponse;
import sweetgirl.croissant.alarm.dto.response.NotificationListResponse;
import sweetgirl.croissant.alarm.entity.Notification;
import sweetgirl.croissant.alarm.entity.NotificationRecipient;
import sweetgirl.croissant.alarm.enums.NotificationChannel;
import sweetgirl.croissant.alarm.enums.NotificationTargetType;
import sweetgirl.croissant.alarm.enums.NotificationType;
import sweetgirl.croissant.alarm.event.PushRequestedEvent;
import sweetgirl.croissant.alarm.repository.NotificationRecipientRepository;
import sweetgirl.croissant.alarm.repository.NotificationRepository;
import sweetgirl.croissant.course.entity.Course;
import sweetgirl.croissant.course.repository.CourseRepository;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.repository.MemberRepository;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

	private static final Long OWNER_ID = 1L;
	private static final Long ACTOR_ID = 2L;
	private static final Long COURSE_ID = 100L;
	private static final Long NOTIFICATION_ID = 900L;

	@Mock
	private NotificationRepository notificationRepository;
	@Mock
	private NotificationRecipientRepository recipientRepository;
	@Mock
	private NotificationRecorder notificationRecorder;
	@Mock
	private ApplicationEventPublisher eventPublisher;
	@Mock
	private CourseRepository courseRepository;
	@Mock
	private MemberRepository memberRepository;

	@InjectMocks
	private NotificationService notificationService;

	// ── 찜 알림 ─────────────────────────────────────────────────────────────

	@Test
	@DisplayName("찜 알림은 내용과 우편함을 함께 만들고 푸시를 요청한다")
	void recordCourseSaved_recordsAndRequestsPush() {
		given(courseRepository.findByIdAndDeletedAtIsNull(COURSE_ID))
			.willReturn(Optional.of(course(COURSE_ID, "연남동 한바퀴")));
		given(notificationRepository.findByActorIdAndTypeAndTargetTypeAndTargetId(
			ACTOR_ID, NotificationType.COURSE_SAVED, NotificationTargetType.COURSE, COURSE_ID))
			.willReturn(Optional.empty());
		given(memberRepository.findByIdAndDeletedAtIsNull(ACTOR_ID))
			.willReturn(Optional.of(member(ACTOR_ID, "포실한 까눌레")));
		Member owner = member(OWNER_ID, "바삭한 크루아상");
		given(memberRepository.findByIdAndDeletedAtIsNull(OWNER_ID)).willReturn(Optional.of(owner));
		givenRecorded();

		notificationService.recordCourseSaved(COURSE_ID, ACTOR_ID, OWNER_ID);

		ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
		verify(notificationRecorder).recordFor(captor.capture(), eq(owner), any());
		Notification saved = captor.getValue();
		assertThat(saved.getType()).isEqualTo(NotificationType.COURSE_SAVED);
		assertThat(saved.getTargetType()).isEqualTo(NotificationTargetType.COURSE);
		assertThat(saved.getTargetTitle()).isEqualTo("연남동 한바퀴");
		// 눌렀을 때 그 빵투어로 — courseId 를 모르는 옛 앱도 link 는 따라간다
		assertThat(saved.getDeepLink()).isEqualTo("/course/" + COURSE_ID);
		// 문구는 앱이 조립한다 — 서버가 문장을 박아 내리면 앱에서 못 고친다.
		assertThat(saved.getTitle()).isNull();

		verify(eventPublisher).publishEvent(PushRequestedEvent.to(NOTIFICATION_ID, OWNER_ID));
	}

	@Test
	@DisplayName("같은 사람이 다시 저장하면 새 줄 없이 우편함 줄을 되살린다")
	void recordCourseSaved_renewsExisting() {
		Notification existing = Notification.activity(
			NotificationType.COURSE_SAVED, member(ACTOR_ID, "포실한 까눌레"),
			NotificationTargetType.COURSE, COURSE_ID, "옛 제목");
		ReflectionTestUtils.setField(existing, "id", NOTIFICATION_ID);
		NotificationRecipient recipient = NotificationRecipient.of(
			existing, member(OWNER_ID, "바삭한 크루아상"), LocalDateTime.now().minusDays(1));
		recipient.markAsRead(LocalDateTime.now().minusHours(1));

		given(courseRepository.findByIdAndDeletedAtIsNull(COURSE_ID))
			.willReturn(Optional.of(course(COURSE_ID, "새 제목")));
		given(notificationRepository.findByActorIdAndTypeAndTargetTypeAndTargetId(
			ACTOR_ID, NotificationType.COURSE_SAVED, NotificationTargetType.COURSE, COURSE_ID))
			.willReturn(Optional.of(existing));
		given(recipientRepository.findByNotificationIdAndMemberId(NOTIFICATION_ID, OWNER_ID))
			.willReturn(Optional.of(recipient));

		notificationService.recordCourseSaved(COURSE_ID, ACTOR_ID, OWNER_ID);

		verify(notificationRecorder, never()).recordFor(any(), any(), any());
		assertThat(recipient.isRead()).isFalse();
		// 되살릴 때 제목도 지금 값으로 맞춘다 — 그 사이 코스 이름이 바뀌었을 수 있다
		assertThat(existing.getTargetTitle()).isEqualTo("새 제목");
	}

	@Test
	@DisplayName("커밋 사이에 코스가 사라졌으면 조용히 넘긴다")
	void recordCourseSaved_skipsWhenCourseGone() {
		given(courseRepository.findByIdAndDeletedAtIsNull(COURSE_ID)).willReturn(Optional.empty());
		given(notificationRepository.findByActorIdAndTypeAndTargetTypeAndTargetId(
			ACTOR_ID, NotificationType.COURSE_SAVED, NotificationTargetType.COURSE, COURSE_ID))
			.willReturn(Optional.empty());
		given(memberRepository.findByIdAndDeletedAtIsNull(anyLong()))
			.willReturn(Optional.of(member(ACTOR_ID, "포실한 까눌레")));

		notificationService.recordCourseSaved(COURSE_ID, ACTOR_ID, OWNER_ID);

		verify(notificationRecorder, never()).recordFor(any(), any(), any());
		verify(eventPublisher, never()).publishEvent(any(Object.class));
	}

	// ── 공지 ────────────────────────────────────────────────────────────────

	@Test
	@DisplayName("공지는 내용 한 줄만 만들고 우편함은 회원 수만큼 늘어난다")
	void sendNotice_createsOneNotificationForEveryone() {
		givenBroadcast(NotificationChannel.PUSH, 1234);

		NoticeSendResponse response = notificationService.sendNotice(
			noticeRequest("일차 나누기가 생겼어요", "본문", "/course/1"));

		assertThat(response.recipientCount()).isEqualTo(1234);

		ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
		verify(notificationRecorder).recordForAllMembers(captor.capture(), any());
		Notification sent = captor.getValue();
		assertThat(sent.getType()).isEqualTo(NotificationType.NOTICE);
		// 공지는 서버가 문구를 갖는다 — 앱 배포 없이 고칠 수 있어야 한다.
		assertThat(sent.getTitle()).isEqualTo("일차 나누기가 생겼어요");
		assertThat(sent.getDeepLink()).isEqualTo("/course/1");

		verify(eventPublisher).publishEvent(PushRequestedEvent.toEveryone(NOTIFICATION_ID));
	}

	@Test
	@DisplayName("채널을 생략한 공지는 지금까지처럼 푸시까지 나간다")
	void sendNotice_defaultsToPush() {
		givenBroadcast(NotificationChannel.PUSH, 10);

		NoticeSendResponse response = notificationService.sendNotice(
			noticeRequest("제목", "본문", null, null));

		assertThat(response.channel()).isEqualTo(NotificationChannel.PUSH);

		ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
		verify(notificationRecorder).recordForAllMembers(captor.capture(), any());
		assertThat(captor.getValue().getChannel()).isEqualTo(NotificationChannel.PUSH);
		verify(eventPublisher).publishEvent(PushRequestedEvent.toEveryone(NOTIFICATION_ID));
	}

	@Test
	@DisplayName("인앱 공지는 알림함에 쌓이되 푸시를 요청하지 않는다")
	void sendNotice_inAppOnly_doesNotRequestPush() {
		givenBroadcast(NotificationChannel.IN_APP, 10);

		NoticeSendResponse response = notificationService.sendNotice(
			noticeRequest("3.2.0 업데이트", "본문", null, NotificationChannel.IN_APP));

		assertThat(response.channel()).isEqualTo(NotificationChannel.IN_APP);

		// 적재는 그대로 — 울리지만 않을 뿐 알림함에서는 볼 수 있어야 한다.
		ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
		verify(notificationRecorder).recordForAllMembers(captor.capture(), any());
		assertThat(captor.getValue().getChannel()).isEqualTo(NotificationChannel.IN_APP);

		verify(eventPublisher, never()).publishEvent(any(PushRequestedEvent.class));
	}

	/** 공지 발송의 반환값을 채널만 바꿔가며 세운다. */
	private void givenBroadcast(NotificationChannel channel, int recipients) {
		Notification notice = Notification.announcement(
			NotificationType.NOTICE, channel, "제목", "본문", "/course/1");
		ReflectionTestUtils.setField(notice, "id", NOTIFICATION_ID);
		given(notificationRecorder.recordForAllMembers(any(), any()))
			.willReturn(new NotificationRecorder.Broadcast(notice, recipients));
	}

	// ── 조회·읽음 ───────────────────────────────────────────────────────────

	@Test
	@DisplayName("목록은 우편함 줄의 id를 준다 — 앱이 그대로 읽음 처리에 돌려준다")
	void getMine_returnsRecipientId() {
		NotificationRecipient recipient = recipient(777L);
		given(recipientRepository.findMine(eq(OWNER_ID), any()))
			.willReturn(new SliceImpl<>(List.of(recipient), PageRequest.of(0, 20), false));

		NotificationListResponse response = notificationService.getMine(OWNER_ID, 0, 20);

		assertThat(response.notifications()).hasSize(1);
		assertThat(response.notifications().get(0).notificationId()).isEqualTo(777L);
		assertThat(response.hasNext()).isFalse();
	}

	@Test
	@DisplayName("전체 읽음은 한 문장으로 끝낸다 — 줄 수만큼 UPDATE가 나가면 안 된다")
	void markAsRead_all() {
		notificationService.markAsRead(OWNER_ID, null);

		verify(recipientRepository).markAllAsRead(eq(OWNER_ID), any());
		verify(recipientRepository, never()).findByMemberIdAndIdIn(anyLong(), any());
	}

	@Test
	@DisplayName("남의 알림 id를 섞어 보내도 조용히 무시된다")
	void markAsRead_ignoresOthers() {
		given(recipientRepository.findByMemberIdAndIdIn(OWNER_ID, List.of(1L, 2L)))
			.willReturn(List.of());

		notificationService.markAsRead(OWNER_ID, readRequest(List.of(1L, 2L)));

		verify(recipientRepository, never()).markAllAsRead(anyLong(), any());
	}

	// ── 도우미 ──────────────────────────────────────────────────────────────

	private void givenRecorded() {
		given(notificationRecorder.recordFor(any(), any(), any()))
			.willAnswer(invocation -> {
				Notification notification = invocation.getArgument(0);
				ReflectionTestUtils.setField(notification, "id", NOTIFICATION_ID);
				return notification;
			});
	}

	private NotificationRecipient recipient(Long id) {
		Notification notification = Notification.activity(
			NotificationType.COURSE_SAVED, member(ACTOR_ID, "포실한 까눌레"),
			NotificationTargetType.COURSE, COURSE_ID, "연남동 한바퀴");
		NotificationRecipient recipient = NotificationRecipient.of(
			notification, member(OWNER_ID, "바삭한 크루아상"), LocalDateTime.now());
		ReflectionTestUtils.setField(recipient, "id", id);
		return recipient;
	}

	private NoticeSendRequest noticeRequest(String title, String body, String link) {
		return noticeRequest(title, body, link, null);
	}

	private NoticeSendRequest noticeRequest(String title, String body, String link,
			NotificationChannel channel) {
		NoticeSendRequest request = new NoticeSendRequest();
		ReflectionTestUtils.setField(request, "title", title);
		ReflectionTestUtils.setField(request, "body", body);
		ReflectionTestUtils.setField(request, "link", link);
		ReflectionTestUtils.setField(request, "channel", channel);
		return request;
	}

	private CourseNotificationReadRequest readRequest(List<Long> ids) {
		CourseNotificationReadRequest request = new CourseNotificationReadRequest();
		ReflectionTestUtils.setField(request, "notificationIds", ids);
		return request;
	}

	private Member member(Long id, String nickname) {
		Member member = Member.builder().nickname(nickname).build();
		ReflectionTestUtils.setField(member, "id", id);
		return member;
	}

	private Course course(Long id, String title) {
		Course course = Course.createCustom(title, member(OWNER_ID, "바삭한 크루아상"), true);
		ReflectionTestUtils.setField(course, "id", id);
		return course;
	}
}
