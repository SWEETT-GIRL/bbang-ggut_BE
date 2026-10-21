package sweetgirl.croissant.dessert.dto.response;

import java.time.LocalDate;

import io.swagger.v3.oas.annotations.media.Schema;
import sweetgirl.croissant.dessert.dto.projection.DessertStoreProjection;
import sweetgirl.croissant.dessert.enums.DessertGrade;

@Schema(description = "이 디저트를 파는 곳")
public record DessertStoreResponse(
		@Schema(description = "빵긋 가게 id. 편의점 지점은 우리 가게가 아니라 null 이고, 상세로도 못 간다") Long storeId,

		String name,

		String address,

		@Schema(description = "가게 대표 사진 키. 편의점 지점은 사진이 없어 null") String thumbnailImageKey,

		double latitude,

		double longitude,

		int distanceMeters,

		@Schema(description = "근거의 세기. 편의점·브랜드 지점은 null") DessertGrade grade,

		@Schema(description = "이 가게를 이 디저트에 붙인 블로그·인스타 근거 수") Integer evidenceCount,

		@Schema(description = "가장 최근 근거의 날짜. 화면은 이걸로 \"3일 전 후기\"라고 적는다") LocalDate lastEvidenceAt,

		@Schema(description = "편의점일 때 GS25 등", example = "GS25") String brand) {

	public static DessertStoreResponse of(DessertStoreProjection projection, String thumbnailImageKey, String brand) {
		return new DessertStoreResponse(
			projection.getStoreId(),
			projection.getName(),
			projection.getAddress(),
			thumbnailImageKey,
			projection.getLatitude(),
			projection.getLongitude(),
			(int)Math.round(projection.getDistanceMeters()),
			toGrade(projection.getGrade()),
			projection.getEvidenceCount(),
			projection.getLastEvidenceAt(),
			brand);
	}

	private static DessertGrade toGrade(String grade) {
		return grade == null ? null : DessertGrade.valueOf(grade);
	}
}
