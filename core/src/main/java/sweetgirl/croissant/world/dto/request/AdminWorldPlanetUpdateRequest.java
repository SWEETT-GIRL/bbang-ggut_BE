package sweetgirl.croissant.world.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** 비워 보낸 칸은 지금 값을 그대로 둔다. */
public record AdminWorldPlanetUpdateRequest(
		@Size(max = 30) String name,
		@Min(1) Integer sortOrder,
		@Positive Integer span,
		@Size(max = 30) String unlockAfter,
		Boolean selectable,
		@Min(0) Integer rouletteSize,
		@Min(0) Integer adsPerWake,
		Boolean referralOk,
		@Size(max = 255) String imageKey,
		Boolean active) {
}
