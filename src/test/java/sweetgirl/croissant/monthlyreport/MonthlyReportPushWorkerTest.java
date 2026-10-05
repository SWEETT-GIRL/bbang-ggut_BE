package sweetgirl.croissant.monthlyreport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.alarm.entity.Notification;
import sweetgirl.croissant.alarm.enums.DeliveryStatus;
import sweetgirl.croissant.alarm.enums.NotificationChannel;
import sweetgirl.croissant.alarm.repository.NotificationDeliveryRepository;
import sweetgirl.croissant.alarm.repository.NotificationRepository;
import sweetgirl.croissant.alarm.service.PushDispatcher;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.repository.MemberRepository;
import sweetgirl.croissant.monthlyreport.admin.entity.MonthlyReportPush;
import sweetgirl.croissant.monthlyreport.admin.repository.MonthlyReportPushRepository;
import sweetgirl.croissant.monthlyreport.admin.service.MonthlyReportPushCommandService;
import sweetgirl.croissant.monthlyreport.admin.service.MonthlyReportPushWorker;

class MonthlyReportPushWorkerTest {
	private final MonthlyReportPushRepository pushes = mock(MonthlyReportPushRepository.class);
	private final NotificationRepository notifications = mock(NotificationRepository.class);
	private final MemberRepository members = mock(MemberRepository.class);
	private final NotificationDeliveryRepository deliveries = mock(NotificationDeliveryRepository.class);
	private final PushDispatcher dispatcher = mock(PushDispatcher.class);
	private final MonthlyReportPushCommandService commandService = new MonthlyReportPushCommandService(
		pushes, notifications, members, deliveries);
	private final MonthlyReportPushWorker worker = new MonthlyReportPushWorker(commandService, dispatcher);
	private final Member member = MonthlyReportFixtures.eligibleMember("member");
	private final Notification notification = mock(Notification.class);

	private MonthlyReportPush due() {
		MonthlyReportPush push = MonthlyReportPush.schedule(1L, 123L, Instant.now().minusSeconds(1));
		when(pushes.lock(1L)).thenReturn(Optional.of(push));
		when(dispatcher.dispatchWithResult(any(), any(), any())).thenReturn(PushDispatcher.Result.SKIPPED);
		when(members.findById(123L)).thenReturn(Optional.of(member));
		when(notifications.findById(1L)).thenReturn(Optional.of(notification));
		return push;
	}

	@Test
	void futureReservationDoesNotSend() {
		MonthlyReportPush push = MonthlyReportPush.schedule(1L, 123L, Instant.now().plusSeconds(3600));
		when(pushes.lock(1L)).thenReturn(Optional.of(push));
		worker.sendScheduledReportPush(1L);
		verifyNoInteractions(dispatcher, members, notifications);
		assertThat(push.getAttempts()).isZero();
	}

	@Test
	void alreadySentDeliveryIsNotSentAgain() {
		MonthlyReportPush push = due();
		when(deliveries.existsByNotificationIdAndMemberIdAndChannelAndStatus(1L, 123L, NotificationChannel.PUSH,
			DeliveryStatus.SENT)).thenReturn(true);
		worker.sendScheduledReportPush(1L);
		assertThat(push.isDone()).isTrue();
		verifyNoInteractions(dispatcher);
	}

	@Test
	void noEligibleDeviceCompletesReservation() {
		MonthlyReportPush push = due();
		worker.sendScheduledReportPush(1L);
		worker.sendScheduledReportPush(1L);
		verify(dispatcher).dispatchWithResult(eq(notification), eq(member), any());
		assertThat(push.isDone()).isTrue();
		assertThat(push.getAttempts()).isEqualTo(1);
		assertThat(push.getErrorCode()).isEqualTo("NO_ELIGIBLE_DELIVERY");
	}

	@Test
	void failedSendBacksOffAndStopsAfterFiveAttempts() {
		MonthlyReportPush push = due();
		doThrow(new IllegalStateException("FCM unavailable")).when(dispatcher).dispatchWithResult(any(), any(), any());
		for (int attempt = 1; attempt <= 5; attempt++) {
			ReflectionTestUtils.setField(push, "nextAttemptAt", Instant.now().minusSeconds(1));
			worker.sendScheduledReportPush(1L);
			assertThat(push.getAttempts()).isEqualTo(attempt);
			assertThat(push.getNextAttemptAt()).isAfter(Instant.now().plusSeconds(290L * attempt));
		}
		ReflectionTestUtils.setField(push, "nextAttemptAt", Instant.now().minusSeconds(1));
		worker.sendScheduledReportPush(1L);
		assertThat(push.getAttempts()).isEqualTo(5);
		assertThat(push.getErrorCode()).isEqualTo("PUSH_FAILED");
		assertThat(push.isDone()).isFalse();
		assertThat(push.retry()).isTrue();
		assertThat(push.getTotalAttempts()).isEqualTo(5);
		assertThat(push.getManualRetries()).isEqualTo(1);
		assertThat(push.retry()).isFalse();
	}

	@Test
	void activeClaimExcludesSecondWorkerAndExpiredClaimRejectsOldResult() {
		MonthlyReportPush push = due();
		MonthlyReportPushCommandService.DeliveryClaim first = commandService.claimScheduledPush(1L).orElseThrow();
		assertThat(commandService.claimScheduledPush(1L)).isEmpty();
		ReflectionTestUtils.setField(push, "leaseExpiresAt", Instant.now().minusSeconds(1));
		MonthlyReportPushCommandService.DeliveryClaim second = commandService.claimScheduledPush(1L).orElseThrow();
		commandService.completeScheduledPush(1L, first.token(), PushDispatcher.Result.SENT);
		assertThat(push.isDone()).isFalse();
		commandService.completeScheduledPush(1L, second.token(), PushDispatcher.Result.FAILED);
		assertThat(push.getLeaseToken()).isNull();
		assertThat(push.getAttempts()).isEqualTo(2);
	}

	@Test
	void crashedFifthAttemptCanBeRecoveredAndManuallyRetried() {
		MonthlyReportPush push = due();
		ReflectionTestUtils.setField(push, "attempts", 4);
		commandService.claimScheduledPush(1L).orElseThrow();
		assertThat(push.retry()).isFalse();
		ReflectionTestUtils.setField(push, "leaseExpiresAt", Instant.now().minusSeconds(1));
		assertThat(commandService.claimScheduledPush(1L)).isEmpty();
		assertThat(push.getLeaseToken()).isNull();
		assertThat(push.retry()).isTrue();
	}

}
