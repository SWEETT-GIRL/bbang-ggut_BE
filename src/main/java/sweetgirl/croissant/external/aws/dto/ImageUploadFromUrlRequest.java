package sweetgirl.croissant.external.aws.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class ImageUploadFromUrlRequest {

	@NotBlank(message = "URL은 필수입니다.")
	private String url;

	// null 이면 백엔드가 (기존 최대값 + 1) 부터 자동 부여.
	private Integer displayOrder;
}
