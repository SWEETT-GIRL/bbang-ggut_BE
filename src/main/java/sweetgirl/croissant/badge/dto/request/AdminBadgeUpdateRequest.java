package sweetgirl.croissant.badge.dto.request;

import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import sweetgirl.croissant.badge.enums.ConditionType;

public record AdminBadgeUpdateRequest(
		@Size(max = 255) String imageKey,
		@Size(min = 1, max = 100) String title,
		@Size(min = 1, max = 255) String hint,
		ConditionType conditionType,
		Integer threshold,
		@PositiveOrZero Integer sortOrder,
		Boolean active) {
}
