package sweetgirl.croissant.admin.dto.response;

import java.util.List;

import sweetgirl.croissant.abuse.entity.AbuseReport;

public record AdminHighPriorityReportResponse(
		Long reportId,
		String kind,
		String targetType,
		Long targetId,
		Long reporterId,
		Long reportedMemberId,
		String reasonType,
		String reasonDetail,
		String status,
		String moderationAction,
		long reviewReportCount24h,
		long sensitiveReasonReportCount,
		long memberReviewReportCount24h,
		boolean temporaryHideCandidate,
		boolean sensitiveReasonPriority,
		boolean memberReviewCandidate,
		boolean highPriority,
		int priorityScore,
		List<String> priorityReasons,
		String createdAt,
		String reviewedAt) {

	public static AdminHighPriorityReportResponse of(
			AbuseReport report,
			long reviewReportCount24h,
			long sensitiveReasonReportCount,
			long memberReviewReportCount24h,
			boolean temporaryHideCandidate,
			boolean sensitiveReasonPriority,
			boolean memberReviewCandidate,
			int priorityScore,
			List<String> priorityReasons) {
		return new AdminHighPriorityReportResponse(
			report.getId(),
			"ABUSE_REPORT",
			report.getTargetType().name(),
			report.getTargetId(),
			report.getReporter().getId(),
			report.getReportedMember().getId(),
			report.getReasonType().name(),
			report.getReasonDetail(),
			report.getStatus().name(),
			report.getModerationAction() != null ? report.getModerationAction().name() : null,
			reviewReportCount24h,
			sensitiveReasonReportCount,
			memberReviewReportCount24h,
			temporaryHideCandidate,
			sensitiveReasonPriority,
			memberReviewCandidate,
			temporaryHideCandidate || sensitiveReasonPriority || memberReviewCandidate,
			priorityScore,
			priorityReasons,
			report.getCreatedAt() != null ? report.getCreatedAt().toString() : null,
			report.getReviewedAt() != null ? report.getReviewedAt().toString() : null);
	}
}
