package sweetgirl.croissant.store.dto.response;

import sweetgirl.croissant.external.kakao.dto.KakaoPlace;

public record AddressSearchResult(
		String placeName,
		String address,
		String roadAddress,
		double latitude,
		double longitude) {

	public static AddressSearchResult from(KakaoPlace place) {
		return new AddressSearchResult(
			place.placeName(),
			place.addressName(),
			place.roadAddressName(),
			Double.parseDouble(place.y()),
			Double.parseDouble(place.x()));
	}
}
