package sweetgirl.croissant.monthlyreport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.time.YearMonth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.alarm.entity.Notification;
import sweetgirl.croissant.alarm.enums.NotificationChannel;
import sweetgirl.croissant.alarm.enums.NotificationType;
import sweetgirl.croissant.alarm.event.PushRequestedEvent;
import sweetgirl.croissant.alarm.service.NotificationRecorder;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportNotificationService;

@ExtendWith(MockitoExtension.class)
class MonthlyReportNotificationServiceTest {

	@Mock
	private NotificationRecorder recorder;
	@Mock
	private ApplicationEventPublisher publisher;
	@InjectMocks
	private MonthlyReportNotificationService service;

	@Test
	void recordsOnlyTheOwnerAndRequestsPushWithReportDeepLink() {
		Member member = new Member("소금이");
		ReflectionTestUtils.setField(member, "id", 123L);
		LocalDateTime now = LocalDateTime.of(2026, 1, 1, 10, 0);
		given(recorder.recordFor(any(), eq(member), eq(now))).willAnswer(invocation -> {
			Notification notification = invocation.getArgument(0);
			ReflectionTestUtils.setField(notification, "id", 900L);
			return notification;
		});

		service.recordPublished(member, YearMonth.of(2025, 12), now);

		ArgumentCaptor<Notification> notification = ArgumentCaptor.forClass(Notification.class);
		verify(recorder).recordFor(notification.capture(), eq(member), eq(now));
		assertThat(notification.getValue().getType()).isEqualTo(NotificationType.MONTHLY_REPORT);
		assertThat(notification.getValue().getChannel()).isEqualTo(NotificationChannel.PUSH);
		assertThat(notification.getValue().getTitle()).isEqualTo("12월 빵일지가 도착했어요");
		assertThat(notification.getValue().getDeepLink()).isEqualTo("/reports/monthly/2025-12");
		verify(publisher).publishEvent(PushRequestedEvent.to(900L, 123L));
	}
}
