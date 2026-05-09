package sweetgirl.croissant.store.dto;

import sweetgirl.croissant.external.kakao.dto.KakaoPlace;
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
		String y) {

	public enum Source {
		DB, KAKAO
	}

	public static StoreSearchResponse ofStore(Store store, String thumbnailUrl) {
		return new StoreSearchResponse(
			store.getId(),
			store.getName(),
			store.getAddress(),
			thumbnailUrl,
			Source.DB, store.getKakaoPlaceId(), store.getPhone(), String.valueOf(store.getLocation().getX()),
			String.valueOf(store.getLocation().getY()));
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
			place.y());
	}
}
