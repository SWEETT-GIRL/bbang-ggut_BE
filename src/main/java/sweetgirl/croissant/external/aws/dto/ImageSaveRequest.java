package sweetgirl.croissant.external.aws.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class ImageSaveRequest {
	private Long targetId;

	@NotBlank(message = "파일명은 필수입니다.")
	private String fileName;

	@NotBlank(message = "S3 키는 필수입니다.")
	private String awsKey;

	@NotBlank(message = "MIME 타입은 필수입니다.")
	private String mimeType;
}
