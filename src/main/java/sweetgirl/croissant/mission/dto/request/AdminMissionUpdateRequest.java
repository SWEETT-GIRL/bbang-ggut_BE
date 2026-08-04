package sweetgirl.croissant.mission.dto.request;

import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import sweetgirl.croissant.badge.enums.ConditionType;

public record AdminMissionUpdateRequest(
		@Size(min = 1, max = 100) String title,
		ConditionType conditionType,
		Integer threshold,
		@Size(min = 1, max = 255) String actionDeeplink,
		@Size(max = 50) String badgeCode,
		@PositiveOrZero Integer sortOrder,
		Boolean active) {
}
