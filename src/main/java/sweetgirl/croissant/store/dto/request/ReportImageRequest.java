package sweetgirl.croissant.store.dto.request;

import jakarta.validation.constraints.NotBlank;

public record ReportImageRequest(
		@NotBlank String fileName,
		@NotBlank String contentType,
		@NotBlank String awsKey) {
}
