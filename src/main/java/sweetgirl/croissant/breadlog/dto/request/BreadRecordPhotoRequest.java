package sweetgirl.croissant.breadlog.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record BreadRecordPhotoRequest(
		@NotBlank String fileName,
		@NotBlank String contentType,
		@NotBlank String awsKey,
		@Min(0) int sortOrder,
		/** 원본 픽셀 크기. 둘 다 있어야 쓴다 — 없으면 정사각형으로 보인다 */
		@Min(1) Integer width,
		@Min(1) Integer height,
		/** 수정 화면에서 가져온 원본 사진 ID. 새 파일로 교체해도 같은 사진으로 연결한다. */
		@Positive Long originalPhotoId) {

	public BreadRecordPhotoRequest(String fileName, String contentType, String awsKey, int sortOrder,
			Integer width, Integer height) {
		this(fileName, contentType, awsKey, sortOrder, width, height, null);
	}
}
