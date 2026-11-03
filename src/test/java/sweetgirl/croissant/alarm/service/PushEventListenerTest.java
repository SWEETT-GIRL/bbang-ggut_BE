package sweetgirl.croissant.alarm.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.alarm.entity.Notification;
import sweetgirl.croissant.alarm.enums.NotificationChannel;
import sweetgirl.croissant.alarm.enums.NotificationTargetType;
import sweetgirl.croissant.alarm.enums.NotificationType;
import sweetgirl.croissant.alarm.event.PushRequestedEvent;
import sweetgirl.croissant.alarm.repository.NotificationRecipientRepository;
import sweetgirl.croissant.alarm.repository.NotificationRepository;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.repository.MemberRepository;

@ExtendWith(MockitoExtension.class)
class PushEventListenerTest {

	private static final Long NOTIFICATION_ID = 900L;
	private static final Long MEMBER_ID = 1L;

	@Mock
	private NotificationRepository notificationRepository;
	@Mock
	private NotificationRecipientRepository recipientRepository;
	@Mock
	private MemberRepository memberRepository;
	@Mock
	private PushDispatcher pushDispatcher;
	@Mock
	private PushBatchService pushBatchService;

	@InjectMocks
	private PushEventListener pushEventListener;

	@Test
	@DisplayName("푸시까지 나가는 알림은 그대로 내보낸다")
	void handle_pushChannel_dispatches() {
		Member member = member();
		given(notificationRepository.findById(NOTIFICATION_ID))
			.willReturn(Optional.of(notice(NotificationChannel.PUSH)));
		given(memberRepository.findByIdAndDeletedAtIsNull(MEMBER_ID)).willReturn(Optional.of(member));

		pushEventListener.handle(PushRequestedEvent.to(NOTIFICATION_ID, MEMBER_ID));

		verify(pushDispatcher).dispatch(any(Notification.class), eq(member), any());
	}

	@Test
	@DisplayName("알림함까지만 나가는 알림은 이벤트가 들어와도 FCM으로 가지 않는다")
	void handle_inAppChannel_doesNotDispatch() {
		given(notificationRepository.findById(NOTIFICATION_ID))
			.willReturn(Optional.of(notice(NotificationChannel.IN_APP)));

		pushEventListener.handle(PushRequestedEvent.to(NOTIFICATION_ID, MEMBER_ID));

		// 받을 사람을 찾아보지도 않는다 — 채널만 보고 접는다.
		verify(memberRepository, never()).findByIdAndDeletedAtIsNull(any());
		verify(pushDispatcher, never()).dispatch(any(), any(), any());
	}

	@Test
	@DisplayName("알림이 사라졌으면 조용히 넘긴다")
	void handle_missingNotification_skips() {
		given(notificationRepository.findById(NOTIFICATION_ID)).willReturn(Optional.empty());

		pushEventListener.handle(PushRequestedEvent.to(NOTIFICATION_ID, MEMBER_ID));

		verify(pushDispatcher, never()).dispatch(any(), any(), any());
	}

	@Test
	@DisplayName("팔로잉 새 빵로그처럼 모아서 울리는 종류는 바로 쏘지 않고 대기열에 넣는다")
	void handle_batchedType_enqueues() {
		Member member = member();
		Member actor = Member.builder().nickname("보낸 사람").build();
		ReflectionTestUtils.setField(actor, "id", 7L);
		Notification notification = Notification.communityActivity(NotificationType.FOLLOWING_NEW_RECORD, actor,
			NotificationTargetType.RECORD, 10L, "밀도", "제목", "밀도", "/bbanglog/post/10");
		ReflectionTestUtils.setField(notification, "id", NOTIFICATION_ID);
		given(notificationRepository.findById(NOTIFICATION_ID)).willReturn(Optional.of(notification));
		given(memberRepository.findByIdAndDeletedAtIsNull(MEMBER_ID)).willReturn(Optional.of(member));

		pushEventListener.handle(PushRequestedEvent.to(NOTIFICATION_ID, MEMBER_ID));

		verify(pushBatchService).enqueue(eq(notification), eq(MEMBER_ID), any());
		verify(pushDispatcher, never()).dispatch(any(), any(), any());
	}

	private Notification notice(NotificationChannel channel) {
		Notification notification = Notification.announcement(
			NotificationType.NOTICE, channel, "제목", "본문", null);
		ReflectionTestUtils.setField(notification, "id", NOTIFICATION_ID);
		return notification;
	}

	private Member member() {
		Member member = Member.builder().nickname("빵순이").build();
		ReflectionTestUtils.setField(member, "id", MEMBER_ID);
		return member;
	}
}
