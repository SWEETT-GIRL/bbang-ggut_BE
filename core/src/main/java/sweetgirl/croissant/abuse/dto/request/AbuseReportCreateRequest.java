package sweetgirl.croissant.abuse.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import sweetgirl.croissant.abuse.entity.AbuseReport;
import sweetgirl.croissant.abuse.enums.AbuseReportReasonType;
import sweetgirl.croissant.abuse.enums.AbuseReportTargetType;
import sweetgirl.croissant.member.entity.Member;

public record AbuseReportCreateRequest(
		@NotNull AbuseReportTargetType targetType,
		@NotNull @Positive Long targetId,
		@NotNull AbuseReportReasonType reasonType,
		@Size(max = 500) String reasonDetail) {

	public AbuseReport toAbuseReport(Member reporter, Member reportedMember) {
		return new AbuseReport(
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
