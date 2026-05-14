package sweetgirl.croissant.store.dto.response;

import java.util.Collections;
import java.util.List;

import sweetgirl.croissant.external.kakao.dto.KakaoPlace;
import sweetgirl.croissant.menu.dto.dto.MenuSummaryDto;

public record StoreSearchResponse(
		Long id,
		String name,
		String address,
		String thumbnailUrl,
		String kakaoPlaceId,
		String phone,
		String x,
		String y,
		List<MenuSummaryDto> topMenus) {

	public static StoreSearchResponse ofKakao(KakaoPlace place, Long storeId, String thumbnailUrl,
			List<MenuSummaryDto> topMenus) {
		String address = place.roadAddressName() != null && !place.roadAddressName().isBlank()
			? place.roadAddressName()
			: place.addressName();
		return new StoreSearchResponse(
			storeId,
			place.placeName(),
			address,
			thumbnailUrl,
			place.id(),
			place.phone(),
			place.x(),
			place.y(),
			topMenus != null ? topMenus : Collections.emptyList());
	}
}
