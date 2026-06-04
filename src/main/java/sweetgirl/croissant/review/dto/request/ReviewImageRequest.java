package sweetgirl.croissant.review.dto.request;

import jakarta.validation.constraints.NotBlank;

public record ReviewImageRequest(
		@NotBlank String fileName,
		@NotBlank String contentType,
		@NotBlank String awsKey) {
}
