package sweetgirl.croissant.store.dto.response;

import org.locationtech.jts.geom.Point;

import sweetgirl.croissant.store.entity.SavedStore;
import sweetgirl.croissant.store.entity.Store;

public record SavedStoreResponse(
		Long storeId,
		String storeName,
		String region,
		/** 전체 주소 — 목록에서 "여기가 맞나?"를 가르는 줄. region(시·도 구)만으론 부족했다 */
		String address,
		double latitude,
		double longitude,
		String thumbnailImageKey) {

	public static SavedStoreResponse from(SavedStore savedStore, String thumbnailImageKey) {
		Store store = savedStore.getStore();
		Point location = store.getLocation();
		return new SavedStoreResponse(
			store.getId(),
			store.getName(),
			regionOf(store),
			store.getAddress(),
			location.getY(),
			location.getX(),
			thumbnailImageKey);
	}

	/**
	 * 구(區)만 내려주면 "동구"가 대전인지 부산인지 클라이언트가 알 길이 없다 —
	 * 시·도부터 붙여 "대전 동구"로 내려준다.
	 */
	private static String regionOf(Store store) {
		String sido = store.getRegion1st();
		String gugun = store.getRegion2nd();
		if (sido == null || sido.isBlank()) {
			return gugun;
		}
		if (gugun == null || gugun.isBlank()) {
			return sido;
		}
		return sido + " " + gugun;
	}
}
