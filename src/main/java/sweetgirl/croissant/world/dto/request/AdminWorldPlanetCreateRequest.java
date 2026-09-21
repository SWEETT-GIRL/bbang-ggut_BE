package sweetgirl.croissant.world.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import sweetgirl.croissant.world.enums.PlanetGroup;

/**
 * 새 행성. {@code span} 은 절대 임계값이 아니라 <b>이 행성을 졸업하는 데 필요한 기록 수</b>다.
 * 도착점은 {@code unlockAfter} 사슬을 따라 서버가 누적해 만든다.
 */
public record AdminWorldPlanetCreateRequest(
		@NotBlank @Size(max = 30) String code,
		@NotBlank @Size(max = 30) String name,
		@NotNull PlanetGroup group,
		@NotNull @Min(1) Integer sortOrder,
		@NotNull @Positive Integer span,
		/** 이 행성을 지나야 열린다. 갈래의 첫 행성이면 비운다. */
		@Size(max = 30) String unlockAfter,
		Boolean selectable,
		@Min(0) Integer rouletteSize,
		@Min(0) Integer adsPerWake,
		Boolean referralOk,
		@Size(max = 255) String imageKey) {
}
