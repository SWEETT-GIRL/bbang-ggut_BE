package sweetgirl.croissant.alarm.dto.response;

import java.time.LocalDateTime;

import sweetgirl.croissant.alarm.entity.Notification;
import sweetgirl.croissant.alarm.entity.NotificationRecipient;

/**
 * 제보·신고·제재 결과 알림 한 줄.
 *
 * <p>제보 알림이 통합 알림함으로 들어오면서 저장 위치가 바뀌었지만 이 모양은 그대로 둔다.
 * 앱이 이 필드들로 이의제기 화면을 열고 목록 한 줄을 그린다 — 서버 사정으로 바꿀 이유가 없다.
 *
 * <p>reportId 계열은 알림 내용의 payload에서 꺼낸다. 통합 테이블에 타입별 컬럼을 펼치는 대신
 * 한 덩어리로 담아 두었기 때문이다.
 */
public record ReportNotificationResponse(
		Long id,
		Long reportId,
		Long contributionReportId,
		Long abuseReportId,
		Long moderationActionId,
		String reportKind,
		String action,
		Long storeId,
		String storeName,
		String title,
		String message,
		boolean read,
		LocalDateTime readAt,
		LocalDateTime sentAt) {

	public static ReportNotificationResponse from(NotificationRecipient recipient) {
		Notification notification = recipient.getNotification();
		return new ReportNotificationResponse(
			recipient.getId(),
			longValue(notification, "reportId"),
			longValue(notification, "contributionReportId"),
			longValue(notification, "abuseReportId"),
			longValue(notification, "moderationActionId"),
			notification.payloadValue("reportKind"),
			notification.payloadValue("action"),
			notification.getTargetId(),
			notification.getTargetTitle(),
			notification.getTitle(),
			notification.getBody(),
			recipient.isRead(),
			recipient.getReadAt(),
			recipient.getNotifiedAt());
	}

	/** payload는 문자열 맵이다. 앱에는 숫자로 나가야 해서 여기서 되돌린다. */
	private static Long longValue(Notification notification, String key) {
		String value = notification.payloadValue(key);
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			return Long.valueOf(value);
		} catch (NumberFormatException e) {
			return null;
		}
	}
}
