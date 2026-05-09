package sweetgirl.croissant.store.dto;

import sweetgirl.croissant.external.kakao.dto.KakaoPlace;
import sweetgirl.croissant.store.entity.Store;

public record StoreSearchResponse(
		Long id,
		String name,
		String address,
		Source source,
		String kakaoPlaceId,
		String phone,
		String x,
		String y) {

	public enum Source {
		DB, KAKAO
	}

	public static StoreSearchResponse ofStore(Store store) {
		return new StoreSearchResponse(
			store.getId(),
			store.getName(),
			store.getAddress(),
			Source.DB,
			null, null, null, null);
	}

	public static StoreSearchResponse ofKakao(KakaoPlace place) {
		String address = place.roadAddressName() != null && !place.roadAddressName().isBlank()
			? place.roadAddressName()
			: place.addressName();
		return new StoreSearchResponse(
			null,
			place.placeName(),
			address,
			Source.KAKAO,
			place.id(),
			place.phone(),
			place.x(),
			place.y());
	}
}
