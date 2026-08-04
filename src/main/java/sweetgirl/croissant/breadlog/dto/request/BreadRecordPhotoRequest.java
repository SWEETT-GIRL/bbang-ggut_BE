package sweetgirl.croissant.breadlog.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record BreadRecordPhotoRequest(
		@NotBlank String fileName,
		@NotBlank String contentType,
		@NotBlank String awsKey,
		@Min(0) int sortOrder) {
}
