package sweetgirl.croissant.mission.dto.response;

import sweetgirl.croissant.badge.enums.ConditionType;
import sweetgirl.croissant.mission.entity.Mission;

public record AdminMissionResponse(
		Long id,
		String code,
		String title,
		ConditionType conditionType,
		Integer threshold,
		String actionDeeplink,
		String badgeCode,
		int sortOrder,
		boolean active) {

	public static AdminMissionResponse from(Mission mission) {
		return new AdminMissionResponse(
			mission.getId(),
			mission.getCode(),
			mission.getTitle(),
			mission.getConditionType(),
			mission.getThreshold(),
			mission.getActionDeeplink(),
			mission.getBadgeCode(),
			mission.getSortOrder(),
			mission.isActive());
	}
}
