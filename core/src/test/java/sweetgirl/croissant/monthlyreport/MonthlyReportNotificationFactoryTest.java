package sweetgirl.croissant.monthlyreport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.YearMonth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import sweetgirl.croissant.alarm.entity.Notification;
import sweetgirl.croissant.alarm.enums.NotificationChannel;
import sweetgirl.croissant.alarm.enums.NotificationType;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportNotificationFactory;

class MonthlyReportNotificationFactoryTest {

	@ParameterizedTest
	@CsvSource({"2025-12, 12, PUSH", "2026-01, 1, PUSH", "2026-09, 9, IN_APP"})
	void createsReportNotificationForRequestedMonthAndChannel(String yearMonth, int month,
			NotificationChannel channel) {
		Notification notification = MonthlyReportNotificationFactory.create(YearMonth.parse(yearMonth), channel);

		assertThat(notification.getType()).isEqualTo(NotificationType.MONTHLY_REPORT);
		assertThat(notification.getChannel()).isEqualTo(channel);
		assertThat(notification.getTitle()).isEqualTo("🪐 " + month + "월 빵별일지가 도착했어요!");
		assertThat(notification.getBody()).isEqualTo("우리 " + month + "월엔 어떤 빵을 만났을까? 빵로그와 활동을 함께 돌아봐요.");
		assertThat(notification.getDeepLink()).isEqualTo("/reports/monthly/" + yearMonth);
	}

	@Test
	void rejectsMissingMonthOrChannel() {
		assertThatNullPointerException()
			.isThrownBy(() -> MonthlyReportNotificationFactory.create(null, NotificationChannel.PUSH));
		assertThatNullPointerException()
			.isThrownBy(() -> MonthlyReportNotificationFactory.create(YearMonth.of(2026, 9), null));
	}
}
