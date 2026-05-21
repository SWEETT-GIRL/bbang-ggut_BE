package sweetgirl.croissant.external.aws.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class ImageOrderUpdateRequest {

	@NotEmpty(message = "순서 목록은 비어있을 수 없습니다.")
	@Valid
	private List<ImageOrder> orders;

	@Getter
	@NoArgsConstructor
	public static class ImageOrder {
		@NotNull(message = "이미지 ID는 필수입니다.")
		private Long id;

		@NotNull(message = "순서는 필수입니다.")
		private Integer displayOrder;
	}
}
