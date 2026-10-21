package sweetgirl.croissant.world.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/** 비워 보낸 칸은 지금 값을 그대로 둔다. */
public record AdminWorldStageUpdateRequest(
		@Size(max = 20) String code,
		@Size(max = 30) String name,
		@Min(1) Integer stageOrder,
		@Min(0) Integer offsetAt,
		@Size(max = 255) String imageKey) {
}
