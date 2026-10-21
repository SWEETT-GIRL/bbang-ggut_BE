package sweetgirl.croissant.admin.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import sweetgirl.croissant.abuse.entity.AbuseReport;
import sweetgirl.croissant.abuse.entity.ModerationAction;
import sweetgirl.croissant.abuse.enums.AbuseReportAction;
import sweetgirl.croissant.abuse.enums.ModerationTargetType;

public record AdminModerationActionRequest(
		@NotBlank String action,
		@Size(max = 500) String reason,
		Boolean notifyUser) {

	public ModerationAction toModerationAction(AbuseReport report, AbuseReportAction parsedAction) {
		return new ModerationAction(
			report,
			ModerationTargetType.fromAbuseReportTargetType(report.getTargetType()),
			report.getTargetId(),
			report.getReportedMember(),
			parsedAction,
			moderationReason());
	}

	public String moderationReason() {
		if (reason == null) {
			return null;
		}
		String normalized = reason.trim();
		return normalized.isEmpty() ? null : normalized;
	}
}
