package sweetgirl.croissant.report.dto.response;

import java.time.LocalDateTime;

import sweetgirl.croissant.report.entity.ModerationAppeal;
import sweetgirl.croissant.report.enums.ModerationAppealStatus;

public record ModerationAppealCreateResponse(
		Long appealId,
		Long actionId,
		ModerationAppealStatus status,
		LocalDateTime createdAt) {

	public static ModerationAppealCreateResponse from(ModerationAppeal appeal) {
		return new ModerationAppealCreateResponse(
			appeal.getId(),
			appeal.getModerationAction().getId(),
			appeal.getStatus(),
			appeal.getCreatedAt());
	}
}
