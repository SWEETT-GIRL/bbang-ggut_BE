package sweetgirl.croissant.mission.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import sweetgirl.croissant.badge.enums.ConditionType;

public record AdminMissionCreateRequest(
		@NotBlank @Size(max = 50) @Pattern(regexp = "[a-z0-9]+(?:-[a-z0-9]+)*") String code,
		@NotBlank @Size(max = 100) String title,
		@NotNull ConditionType conditionType,
		Integer threshold,
		@NotBlank @Size(max = 255) String actionDeeplink,
		@Size(max = 50) String badgeCode,
		@PositiveOrZero int sortOrder) {
}
