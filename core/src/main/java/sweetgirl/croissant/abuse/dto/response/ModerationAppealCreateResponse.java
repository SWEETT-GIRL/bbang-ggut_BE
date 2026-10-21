package sweetgirl.croissant.abuse.dto.response;

import java.time.LocalDateTime;

import sweetgirl.croissant.abuse.entity.ModerationAppeal;
import sweetgirl.croissant.abuse.enums.ModerationAppealStatus;

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
