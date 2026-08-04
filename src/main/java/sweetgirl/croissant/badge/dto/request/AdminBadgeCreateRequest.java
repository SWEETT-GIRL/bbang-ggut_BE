package sweetgirl.croissant.badge.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import sweetgirl.croissant.badge.enums.ConditionType;

public record AdminBadgeCreateRequest(
		@NotBlank @Size(max = 50) @Pattern(regexp = "[a-z0-9]+(?:-[a-z0-9]+)*") String code,
		@Size(max = 255) String imageKey,
		@NotBlank @Size(max = 100) String title,
		@NotBlank @Size(max = 255) String hint,
		@NotNull ConditionType conditionType,
		Integer threshold,
		@PositiveOrZero int sortOrder) {
}
