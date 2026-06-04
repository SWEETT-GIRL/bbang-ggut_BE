package sweetgirl.croissant.review.dto.request;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import sweetgirl.croissant.review.enums.TextureTag;

public record ReviewCreateRequest(
		@NotNull BigDecimal rating,
		@NotBlank @Size(max = 50) String repurchaseIntent,
		@NotBlank @Size(max = 50) String sweetness,
		@NotBlank @Size(max = 50) String fillingAmount,
		@NotNull @Size(min = 1, max = 5) List<TextureTag> textureTags,
		String content,
		@Size(max = 5) List<@Valid ReviewImageRequest> images) {
}
