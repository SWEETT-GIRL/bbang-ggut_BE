package sweetgirl.croissant.store.dto.response;

import java.util.Collections;
import java.util.List;

import org.locationtech.jts.geom.Point;

import sweetgirl.croissant.external.kakao.dto.KakaoPlace;
import sweetgirl.croissant.external.opensearch.dto.MatchedField;
import sweetgirl.croissant.menu.dto.dto.MenuSummaryDto;
import sweetgirl.croissant.store.entity.Store;

public record StoreSearchResponse(
		Long id,
		String name,
		String address,
		String thumbnailUrl,
		String kakaoPlaceId,
		String phone,
		String x,
		String y,
		List<MenuSummaryDto> topMenus,
		List<MatchedField> matchedFields) {

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
			topMenus != null ? topMenus : Collections.emptyList(),
			Collections.emptyList());
	}

	public static StoreSearchResponse ofDb(Store store, String thumbnailUrl, List<MenuSummaryDto> topMenus,
			List<MatchedField> matchedFields) {
		Point location = store.getLocation();
		String longitude = location != null ? String.valueOf(location.getX()) : null;
		String latitude = location != null ? String.valueOf(location.getY()) : null;
		return new StoreSearchResponse(
			store.getId(),
			store.getName(),
			store.getAddress(),
			thumbnailUrl,
			store.getKakaoPlaceId(),
			store.getPhone(),
			longitude,
			latitude,
			topMenus != null ? topMenus : Collections.emptyList(),
			matchedFields != null ? matchedFields : Collections.emptyList());
	}
}
