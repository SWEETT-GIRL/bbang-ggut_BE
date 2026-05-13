package sweetgirl.croissant.store.dto.response;

import java.util.Collections;
import java.util.List;

import sweetgirl.croissant.external.kakao.dto.KakaoPlace;
import sweetgirl.croissant.menu.dto.dto.MenuSummaryDto;
import sweetgirl.croissant.store.entity.Store;

public record StoreSearchResponse(
		Long id,
		String name,
		String address,
		String thumbnailUrl,
		Source source,
		String kakaoPlaceId,
		String phone,
		String x,
		String y,
		List<MenuSummaryDto> topMenus) {

	public enum Source {
		DB, KAKAO
	}

	public static StoreSearchResponse ofStore(Store store, String thumbnailUrl, List<MenuSummaryDto> topMenus) {
		return new StoreSearchResponse(
			store.getId(),
			store.getName(),
			store.getAddress(),
			thumbnailUrl,
			Source.DB, store.getKakaoPlaceId(), store.getPhone(), String.valueOf(store.getLocation().getX()),
			String.valueOf(store.getLocation().getY()), topMenus);
	}

	public static StoreSearchResponse ofKakao(KakaoPlace place) {
		String address = place.roadAddressName() != null && !place.roadAddressName().isBlank()
			? place.roadAddressName()
			: place.addressName();
		return new StoreSearchResponse(
			null,
			place.placeName(),
			address,
			null,
			Source.KAKAO,
			place.id(),
			place.phone(),
			place.x(),
			place.y(), Collections.emptyList());
	}
}
