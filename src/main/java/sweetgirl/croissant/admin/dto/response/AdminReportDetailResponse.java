package sweetgirl.croissant.admin.dto.response;

import sweetgirl.croissant.report.entity.UserReport;

public record AdminReportDetailResponse(
		Long reportId,
		String kind,
		String requestType,
		String targetType,
		Long targetId,
		String reasonType,
		String reasonDetail,
		String status,
		String moderationAction,
		Long reporterId,
		Long reportedMemberId,
		String createdAt,
		String reviewedAt,
		Object target) {

	public static AdminReportDetailResponse ofUserReport(UserReport report, Object target) {
		return new AdminReportDetailResponse(
			report.getId(),
			"USER_REPORT",
			report.getTargetType().name(),
			report.getTargetType().name(),
			report.getTargetId(),
			report.getReasonType().name(),
			report.getReasonDetail(),
			report.getStatus().name(),
			report.getModerationAction().name(),
			report.getReporter().getId(),
			report.getReportedMember().getId(),
			report.getCreatedAt() != null ? report.getCreatedAt().toString() : null,
			report.getReviewedAt() != null ? report.getReviewedAt().toString() : null,
			target);
	}

	public static AdminReportDetailResponse ofReport(AdminReportResponse report) {
		return new AdminReportDetailResponse(
			report.id(),
			report.kind(),
			report.requestType(),
			report.targetType(),
			report.targetId(),
			report.reasonType(),
			report.reasonDetail(),
			report.status(),
			null,
			report.memberId(),
			report.reportedMemberId(),
			report.createdAt(),
			report.reviewedAt(),
			report);
	}
}
