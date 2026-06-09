package sweetgirl.croissant.admin.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import sweetgirl.croissant.report.entity.ModerationAction;
import sweetgirl.croissant.report.entity.UserReport;
import sweetgirl.croissant.report.enums.ModerationTargetType;
import sweetgirl.croissant.report.enums.UserReportAction;

public record AdminModerationActionRequest(
		@NotBlank String action,
		@Size(max = 500) String reason,
		Boolean notifyUser) {

	public ModerationAction toModerationAction(UserReport report, UserReportAction parsedAction) {
		return new ModerationAction(
			report,
			ModerationTargetType.fromReportTargetType(report.getTargetType()),
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
