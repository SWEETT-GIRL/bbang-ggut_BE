package sweetgirl.croissant.external.aws.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class ImageUploadRequest {
	@NotBlank(message = "경로(store 또는 menu)는 필수입니다.")
	private String domain;

	private Long targetId;

	@NotBlank(message = "MIME 타입은 필수입니다.")
	private String mimeType;
}
