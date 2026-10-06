package sweetgirl.croissant.monthlyreport.service;

import java.time.YearMonth;
import java.util.Objects;

import sweetgirl.croissant.alarm.entity.Notification;
import sweetgirl.croissant.alarm.enums.NotificationChannel;
import sweetgirl.croissant.alarm.enums.NotificationType;

/** 관리자·배치 발행에서 사용하는 월간 보고서 알림 문구와 이동 경로. */
public final class MonthlyReportNotificationFactory {

	private MonthlyReportNotificationFactory() {
		// 알림 생성만 제공한다.
	}

	public static Notification create(YearMonth month, NotificationChannel channel) {
		Objects.requireNonNull(month, "month");
		Objects.requireNonNull(channel, "channel");
		String monthLabel = month.getMonthValue() + "월";
		return Notification.announcement(NotificationType.MONTHLY_REPORT, channel,
			"🪐 " + monthLabel + " 빵별일지가 도착했어요!",
			"우리 " + monthLabel + "엔 어떤 빵을 만났을까? 빵로그와 활동을 함께 돌아봐요.",
			"/reports/monthly/" + month);
	}
}
