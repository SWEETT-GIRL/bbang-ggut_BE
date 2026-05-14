package sweetgirl.croissant.admin.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "메뉴 등록 요청")
public record AdminMenuCreateRequest(
		@Schema(description = "가게 ID", example = "1") Long bakeryId,
		@Schema(description = "메뉴 이름", example = "크루아상") @NotBlank String name,
		@Schema(description = "가격 (숫자+'원' 형식)", example = "3500원") String price,
		@Schema(description = "메뉴 설명") String desc,
		@Schema(description = "카테고리 코드 (MenuKind 코드명, 예: BREAD, CAKE, COOKIE)", example = "BREAD") String category,
		@Schema(description = "상태 (active | inactive)", example = "active") String status) {
}
