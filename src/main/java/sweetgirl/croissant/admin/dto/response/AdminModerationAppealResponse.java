package sweetgirl.croissant.admin.dto.response;

import sweetgirl.croissant.report.entity.ModerationAction;
import sweetgirl.croissant.report.entity.ModerationAppeal;

public record AdminModerationAppealResponse(
		Long appealId,
		Long actionId,
		Long memberId,
		String targetType,
		Long targetId,
		String moderationAction,
		String moderationReason,
		String content,
		String status,
		String adminMemo,
		String reviewedAt,
		String createdAt,
		String updatedAt) {

	public static AdminModerationAppealResponse from(ModerationAppeal appeal) {
		ModerationAction action = appeal.getModerationAction();
		return new AdminModerationAppealResponse(
			appeal.getId(),
			action.getId(),
			appeal.getMember().getId(),
			action.getTargetType().name(),
			action.getTargetId(),
			action.getAction().name(),
			action.getReason(),
			appeal.getContent(),
			appeal.getStatus().name(),
			appeal.getAdminMemo(),
			appeal.getReviewedAt() != null ? appeal.getReviewedAt().toString() : null,
			appeal.getCreatedAt() != null ? appeal.getCreatedAt().toString() : null,
			appeal.getUpdatedAt() != null ? appeal.getUpdatedAt().toString() : null);
	}
}
