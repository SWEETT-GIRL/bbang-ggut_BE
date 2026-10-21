package sweetgirl.croissant.store.dto.response;

import sweetgirl.croissant.store.dto.projection.NearbyStoreProjection;
import sweetgirl.croissant.store.enums.StoreCategory;

public record NearbyStoreResponse(
		Long id,
		String name,
		String region,
		String thumbnailImageKey,
		int distanceMeters,
		/** 가게 좌표 — 앱이 빵투어 이전 가게와의 구간 거리를 잰다 */
		Double latitude,
		Double longitude,
		/** 가게 분류(BAKERY·BAGEL …). 외부 카테고리를 아직 못 받은 가게는 null */
		StoreCategory category) {
	public static NearbyStoreResponse of(NearbyStoreProjection projection, String thumbnailImageKey,
			int distanceMeters) {
		return new NearbyStoreResponse(
			projection.getId(),
			projection.getName(),
			joinRegion(projection.getRegion1st(), projection.getRegion2nd()),
			thumbnailImageKey,
			distanceMeters,
			projection.getLatitude(),
			projection.getLongitude(),
			projection.getCategory() == null ? null : StoreCategory.valueOf(projection.getCategory()));
	}

	private static String joinRegion(String region1st, String region2nd) {
		if (region1st == null) {
			return region2nd;
		}
		if (region2nd == null) {
			return region1st;
		}
		return region1st + " " + region2nd;
	}
}
