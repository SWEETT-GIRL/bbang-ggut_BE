package sweetgirl.croissant.alarm.dto.response;

import java.time.LocalDateTime;

import sweetgirl.croissant.alarm.entity.ReportNotification;

public record ReportNotificationResponse(
		Long id,
		Long reportId,
		String reportKind,
		String action,
		Long storeId,
		String storeName,
		String title,
		String message,
		boolean read,
		LocalDateTime readAt,
		LocalDateTime sentAt) {

	public static ReportNotificationResponse from(ReportNotification notification) {
		return new ReportNotificationResponse(
			notification.getId(),
			notification.getReportId(),
			notification.getReportKind().name(),
			notification.getAction().name(),
			notification.getStore() != null ? notification.getStore().getId() : null,
			notification.getStore() != null ? notification.getStore().getName() : null,
			notification.getTitle(),
			notification.getMessage(),
			notification.getReadAt() != null,
			notification.getReadAt(),
			notification.getSentAt());
	}
}
