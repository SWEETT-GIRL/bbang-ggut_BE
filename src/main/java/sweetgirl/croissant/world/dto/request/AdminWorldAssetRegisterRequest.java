package sweetgirl.croissant.world.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** 올리기를 마친 뒤 그 키를 카탈로그에 등록한다. */
public record AdminWorldAssetRegisterRequest(
		@NotBlank @Size(max = 20) @Pattern(regexp = "[a-z0-9_-]+") String pose,
		@NotBlank @Size(max = 255) String imageKey,
		@Positive Integer width,
		@Positive Integer height) {
}
