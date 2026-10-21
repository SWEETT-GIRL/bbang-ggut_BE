package sweetgirl.croissant.menu.dto.request;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.external.aws.dto.ImageSaveRequest;

@Getter
@NoArgsConstructor
public class MenuAddReportRequest {

	@NotNull(message = "메뉴 카테고리는 필수입니다.")
	private Long menuCategoryId;

	@NotBlank(message = "메뉴명은 필수입니다.")
	@Size(max = 100, message = "메뉴명은 100자 이하여야 합니다.")
	private String name;

	@Positive(message = "가격은 양수여야 합니다.")
	private Integer price;

	@Size(max = 500, message = "메뉴 설명은 500자 이하여야 합니다.")
	private String description;

	@NotEmpty(message = "사진은 최소 1장 이상 필수입니다.")
	private List<ImageSaveRequest> images;
}
