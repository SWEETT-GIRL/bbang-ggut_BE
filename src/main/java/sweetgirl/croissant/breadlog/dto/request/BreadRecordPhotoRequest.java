package sweetgirl.croissant.breadlog.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record BreadRecordPhotoRequest(
		@NotBlank String fileName,
		@NotBlank String contentType,
		@NotBlank String awsKey,
		@Min(0) int sortOrder,
		/** 원본 픽셀 크기. 둘 다 있어야 쓴다 — 없으면 정사각형으로 보인다 */
		@Min(1) Integer width,
		@Min(1) Integer height) {
}
