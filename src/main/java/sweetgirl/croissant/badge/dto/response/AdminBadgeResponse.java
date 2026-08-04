package sweetgirl.croissant.badge.dto.response;

import java.time.LocalDateTime;

import sweetgirl.croissant.badge.entity.Badge;
import sweetgirl.croissant.badge.enums.ConditionType;

public record AdminBadgeResponse(
		Long id,
		String code,
		String imageKey,
		String title,
		String hint,
		ConditionType conditionType,
		Integer threshold,
		int sortOrder,
		boolean active,
		LocalDateTime createdAt) {

	public static AdminBadgeResponse from(Badge badge) {
		return new AdminBadgeResponse(
			badge.getId(),
			badge.getCode(),
			badge.getImageKey(),
			badge.getTitle(),
			badge.getHint(),
			badge.getConditionType(),
			badge.getThreshold(),
			badge.getSortOrder(),
			badge.isActive(),
			badge.getCreatedAt());
	}
}
