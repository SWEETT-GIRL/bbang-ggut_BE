package sweetgirl.croissant.breadlog.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import sweetgirl.croissant.breadlog.dto.request.BreadTagRatingRequest;

/** 발행 후 변경하지 않는 체험 재료. 사진과 평가를 항상 같은 버전으로 제공한다. */
public record TutorialTemplateContent(
		@NotBlank @Size(max = 80) String title,
		@NotBlank @Pattern(regexp = "onboarding/tutorials/[a-f0-9-]{36}\\.(png|webp|jpg)") String imageKey,
		@NotBlank @Pattern(regexp = "image/(png|webp|jpeg)") String contentType,
		@NotNull @Min(1) @Max(8000) Integer width,
		@NotNull @Min(1) @Max(8000) Integer height,
		@NotBlank @Size(max = 100) String storeName,
		@NotBlank @Size(max = 100) String breadName,
		@NotNull @DecimalMin("0.0") @DecimalMax("1.0") Double x,
		@NotNull @DecimalMin("0.0") @DecimalMax("1.0") Double y,
		@NotBlank @Pattern(regexp = "(round|rect|cloud|burst)\\."
			+ "(dark|kraft|lined|grid|dots|mint|sky|lavender)(\\.up)?") String style,
		@NotNull @Valid BreadTagRatingRequest rating,
		@NotBlank @Size(max = 500) String memo,
		@NotBlank @Pattern(regexp = "croissant|melonpan|soboro|bagel|anpan") String towerBread) {
}
