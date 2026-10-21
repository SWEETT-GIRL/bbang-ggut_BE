package sweetgirl.croissant.admin.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "메뉴 이름 단위 분류 확정 요청")
public record AdminMenuClassificationConfirmRequest(
		@Schema(description = "메뉴 이름 (정규화 전, 검수 큐의 name 그대로)", example = "슈크림빵") @NotBlank String name,
		@Schema(description = "MenuKind 이름", example = "크림빵") @NotBlank String kind,
		@Schema(description = "빵집 먹거리면 true, 음료·굿즈면 false. 생략하면 true", example = "true") Boolean bakeryItem) {

	public boolean bakeryItemOrDefault() {
		return bakeryItem == null || bakeryItem;
	}
}
