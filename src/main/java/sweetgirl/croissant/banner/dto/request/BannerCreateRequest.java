package sweetgirl.croissant.banner.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class BannerCreateRequest {

	@NotBlank(message = "썸네일 파일명은 필수입니다.")
	private String thumbnailName;

	@NotBlank(message = "썸네일 MIME 타입은 필수입니다.")
	private String thumbnailMimeType;

	@NotBlank(message = "썸네일 S3 키는 필수입니다.")
	private String thumbnailKey;

	@NotBlank(message = "상세 파일명은 필수입니다.")
	private String detailName;

	@NotBlank(message = "상세 MIME 타입은 필수입니다.")
	private String detailMimeType;

	@NotBlank(message = "상세 S3 키는 필수입니다.")
	private String detailKey;
}
