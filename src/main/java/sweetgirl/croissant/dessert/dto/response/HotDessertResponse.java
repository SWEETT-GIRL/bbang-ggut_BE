package sweetgirl.croissant.dessert.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import sweetgirl.croissant.dessert.entity.DessertItem;
import sweetgirl.croissant.dessert.enums.DessertItemType;
import sweetgirl.croissant.dessert.enums.DessertTrendStatus;

@Schema(description = "지도 칩 줄에 세울 요즘 뜨는 디저트")
public record HotDessertResponse(
		Long id,

		@Schema(description = "칩에 찍히는 이름", example = "피자설기") String name,

		@Schema(description = "CONVENIENCE 면 편의점 지점을, 나머지는 빵긋 가게를 보여준다") DessertItemType itemType,

		@Schema(description = "CONVENIENCE·CHAIN 의 판매 브랜드. GENERIC 은 null", example = "GS25") String brand,

		@Schema(description = "dessert-radar 판정. 칩의 불꽃은 HOT 에만 붙는다") DessertTrendStatus trendStatus) {

	public static HotDessertResponse from(DessertItem dessertItem) {
		return new HotDessertResponse(
			dessertItem.getId(),
			dessertItem.getName(),
			dessertItem.getItemType(),
			dessertItem.getBrand(),
			dessertItem.getTrendStatus());
	}
}
