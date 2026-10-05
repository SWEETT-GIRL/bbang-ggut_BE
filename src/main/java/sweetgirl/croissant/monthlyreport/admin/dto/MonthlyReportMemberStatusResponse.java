package sweetgirl.croissant.monthlyreport.admin.dto;

import java.time.Instant;

import sweetgirl.croissant.monthlyreport.repository.MonthlyReportNotificationRepository.MemberStatus;

public record MonthlyReportMemberStatusResponse(Long memberId, Instant publishedAt, Instant firstViewedAt,
		Instant notificationReadAt, String pushState,
		Instant sendAt, Instant nextAttemptAt, Instant completedAt, int totalAttempts, int manualRetries,
		String errorCode) {
	public static MonthlyReportMemberStatusResponse from(MemberStatus status) {
		return new MonthlyReportMemberStatusResponse(status.getMemberId(), status.getPublishedAt(),
			status.getFirstViewedAt(),
			status.getNotificationReadAt(), status.getPushState(), status.getSendAt(), status.getNextAttemptAt(),
			status.getCompletedAt(),
			status.getTotalAttempts(), status.getManualRetries(), status.getErrorCode());
	}
}
