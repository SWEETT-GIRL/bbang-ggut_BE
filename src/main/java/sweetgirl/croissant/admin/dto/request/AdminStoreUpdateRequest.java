package sweetgirl.croissant.admin.dto.request;

import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;
import sweetgirl.croissant.store.enums.StoreCategory;

@Schema(description = "가게 수정 요청 (모든 필드 선택적)")
public record AdminStoreUpdateRequest(
		@Schema(description = "가게 이름") String name,
		@Schema(description = "가게 주소") String address,
		@Schema(description = "좌표 (위도/경도)") CoordRequest coord,
		@Schema(description = "전화번호") String phone,
		@Schema(description = "가게 상태 (active | inactive)") String status,
		@Schema(description = "보관 처리 일시 — non-null이면 상태를 DELETED로 변경") String archivedAt,
		@Schema(description = "가게 카테고리 (WESTERN)") StoreCategory category,
		@Schema(description = "영업시간 (mon~sun 키, closed=true면 휴무)") Map<String, BusinessHourRequest> hours) {

	@Schema(description = "좌표")
	public record CoordRequest(
			@Schema(description = "위도", example = "37.5665") double latitude,
			@Schema(description = "경도", example = "126.9780") double longitude) {
	}
}
