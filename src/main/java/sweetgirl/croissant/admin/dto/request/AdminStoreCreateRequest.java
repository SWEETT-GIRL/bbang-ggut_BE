package sweetgirl.croissant.admin.dto.request;

import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import sweetgirl.croissant.store.enums.StoreCategory;

@Schema(description = "가게 등록 요청")
public record AdminStoreCreateRequest(
		@Schema(description = "가게 이름", example = "뚱이네 빵집") @NotBlank String name,
		@Schema(description = "가게 주소", example = "서울 강남구 테헤란로 1길") @NotBlank String address,
		@Schema(description = "좌표 (위도/경도)") @NotNull CoordRequest coord,
		@Schema(description = "전화번호", example = "02-1234-5678") String phone,
		@Schema(description = "네이버 플레이스 ID", example = "123456789") String naverPlaceId,
		@Schema(description = "카카오 플레이스 ID (legacy)", example = "123456789") String kakaoPlaceId,
		@Schema(description = "가게 상태 (active | inactive)", example = "active") @NotBlank String status,
		@Schema(description = "가게 카테고리 (WESTERN)") StoreCategory category,
		@Schema(description = "영업시간 (mon~sun 키, closed=true면 휴무)") Map<String, BusinessHourRequest> hours) {

	@Schema(description = "좌표")
	public record CoordRequest(
			@Schema(description = "위도", example = "37.5665") double latitude,
			@Schema(description = "경도", example = "126.9780") double longitude) {
	}
}
