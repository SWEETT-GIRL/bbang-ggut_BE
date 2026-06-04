package sweetgirl.croissant.admin.dto.response;

import sweetgirl.croissant.report.entity.ModerationAction;

public record AdminModerationActionResponse(
		Long actionId,
		Long reportId,
		String targetType,
		Long targetId,
		Long memberId,
		String action,
		String reason,
		String createdAt) {

	public static AdminModerationActionResponse from(ModerationAction moderationAction) {
		return new AdminModerationActionResponse(
			moderationAction.getId(),
			moderationAction.getReport() != null ? moderationAction.getReport().getId() : null,
			moderationAction.getTargetType().name(),
			moderationAction.getTargetId(),
			moderationAction.getMember() != null ? moderationAction.getMember().getId() : null,
			moderationAction.getAction().name(),
			moderationAction.getReason(),
			moderationAction.getCreatedAt() != null ? moderationAction.getCreatedAt().toString() : null);
	}
}
