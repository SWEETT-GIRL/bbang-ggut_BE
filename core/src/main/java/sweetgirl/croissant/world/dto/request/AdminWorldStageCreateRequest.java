package sweetgirl.croissant.world.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 고도 단계 하나. {@code offsetAt} 은 <b>행성 도착점 기준 상대 위치</b>다(0 = 도착 즉시).
 * 절대 누적값이 아니라서 행성 순서가 바뀌어도 그대로 쓸 수 있다.
 */
public record AdminWorldStageCreateRequest(
		@NotBlank @Size(max = 20) String code,
		@NotBlank @Size(max = 30) String name,
		@NotNull @Min(1) Integer stageOrder,
		@NotNull @Min(0) Integer offsetAt,
		@Size(max = 255) String imageKey) {
}
