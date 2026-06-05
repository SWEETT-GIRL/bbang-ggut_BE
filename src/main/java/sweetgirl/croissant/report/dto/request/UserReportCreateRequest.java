package sweetgirl.croissant.report.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.report.entity.UserReport;
import sweetgirl.croissant.report.enums.ReportReasonType;
import sweetgirl.croissant.report.enums.ReportTargetType;

public record UserReportCreateRequest(
		@NotNull ReportTargetType targetType,
		@NotNull @Positive Long targetId,
		@NotNull ReportReasonType reasonType,
		@Size(max = 500) String reasonDetail) {

	public UserReport toUserReport(Member reporter, Member reportedMember) {
		return new UserReport(
			reporter,
			targetType,
			targetId,
			reportedMember,
			reasonType,
			normalizedReasonDetail());
	}

	private String normalizedReasonDetail() {
		if (reasonDetail == null) {
			return null;
		}
		String normalized = reasonDetail.trim();
		return normalized.isEmpty() ? null : normalized;
	}
}
