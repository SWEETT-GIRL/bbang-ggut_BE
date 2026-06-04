package sweetgirl.croissant.external.aws.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.common.enums.ImageStatus;

@Getter
@NoArgsConstructor
public class ImageStatusUpdateRequest {

	@NotBlank(message = "awsKey는 필수입니다.")
	private String awsKey;

	@NotNull(message = "status는 필수입니다.")
	private ImageStatus status;
}
