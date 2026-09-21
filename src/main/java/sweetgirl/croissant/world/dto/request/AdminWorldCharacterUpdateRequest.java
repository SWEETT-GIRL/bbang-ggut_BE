package sweetgirl.croissant.world.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/** 비워 보낸 칸은 지금 값을 그대로 둔다. */
public record AdminWorldCharacterUpdateRequest(
		@Size(max = 30) String name,
		@Size(max = 30) String planetCode,
		@Min(1) Integer sortOrder,
		Boolean mascot,
		Boolean active) {
}
