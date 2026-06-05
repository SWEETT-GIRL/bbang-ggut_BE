package sweetgirl.croissant.admin.dto.response;

import java.util.List;

public record AdminUserReportStatsResponse(
		long totalReportCount,
		long pendingReportCount,
		long acceptedReportCount,
		long rejectedReportCount,
		long last24HoursReportCount,
		long pendingReviewHideCandidateCount,
		long pendingSensitiveReasonPriorityCount,
		long pendingMemberReviewCandidateCount,
		int lookbackHours,
		int reviewHideCandidateReporterThreshold,
		int sensitiveReasonPriorityReporterThreshold,
		int memberReviewCandidateTargetThreshold,
		List<String> sensitiveReasonTypes) {
}
