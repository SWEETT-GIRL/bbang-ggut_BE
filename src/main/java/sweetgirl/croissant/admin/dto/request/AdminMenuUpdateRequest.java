package sweetgirl.croissant.admin.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "메뉴 수정 요청 (모든 필드 선택적)")
public record AdminMenuUpdateRequest(
		@Schema(description = "메뉴 이름") String name,
		@Schema(description = "가격 (숫자+'원' 형식)", example = "3500원") String price,
		@Schema(description = "메뉴 설명") String desc,
		@Schema(description = "카테고리 코드 (MenuKind 코드명, 예: BREAD, CAKE, COOKIE)") String category,
		@Schema(description = "상태 — 'inactive' 설정 시 soft-delete (복구 불가)") String status) {
}
