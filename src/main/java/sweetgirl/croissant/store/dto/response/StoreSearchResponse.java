package sweetgirl.croissant.store.dto.response;

import java.util.Collections;
import java.util.List;

import org.locationtech.jts.geom.Point;

import sweetgirl.croissant.external.kakao.dto.KakaoPlace;
import sweetgirl.croissant.menu.dto.dto.MenuSummaryDto;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.enums.MatchedField;

public record StoreSearchResponse(
		Long id,
		String name,
		String address,
		String thumbnailImageKey,
		String kakaoPlaceId,
		String phone,
		String x,
		String y,
		List<MenuSummaryDto> topMenus,
		List<MatchedField> matchedFields) {

	public static StoreSearchResponse ofKakao(KakaoPlace place, Long storeId, String thumbnailImageKey,
			List<MenuSummaryDto> topMenus) {
		String address = place.roadAddressName() != null && !place.roadAddressName().isBlank()
			? place.roadAddressName()
			: place.addressName();
		return new StoreSearchResponse(
			storeId,
			place.placeName(),
			address,
			thumbnailImageKey,
			place.id(),
			place.phone(),
			place.x(),
			place.y(),
			topMenus != null ? topMenus : Collections.emptyList(),
			Collections.emptyList());
	}

	public static StoreSearchResponse ofDb(Store store, String thumbnailImageKey, List<MenuSummaryDto> topMenus,
			List<MatchedField> matchedFields) {
		Point location = store.getLocation();
		String longitude = location != null ? String.valueOf(location.getX()) : null;
		String latitude = location != null ? String.valueOf(location.getY()) : null;
		return new StoreSearchResponse(
			store.getId(),
			store.getName(),
			store.getAddress(),
			thumbnailImageKey,
			store.getKakaoPlaceId(),
			store.getPhone(),
			longitude,
			latitude,
			topMenus != null ? topMenus : Collections.emptyList(),
			matchedFields != null ? matchedFields : Collections.emptyList());
	}
}
