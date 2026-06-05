package sweetgirl.croissant.admin.dto.request;

import jakarta.validation.constraints.Size;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.report.entity.ModerationAction;
import sweetgirl.croissant.report.enums.ReportTargetType;
import sweetgirl.croissant.report.enums.UserReportAction;

public record AdminProfileResetRequest(
		@Size(max = 500) String reason) {

	public ModerationAction toModerationAction(Member member) {
		return new ModerationAction(
			null,
			ReportTargetType.MEMBER_PROFILE,
			member.getId(),
			member,
			UserReportAction.RESET_PROFILE,
			normalizedReason());
	}

	private String normalizedReason() {
		if (reason == null) {
			return null;
		}
		String normalized = reason.trim();
		return normalized.isEmpty() ? null : normalized;
	}
}
