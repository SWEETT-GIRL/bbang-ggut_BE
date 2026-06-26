package sweetgirl.croissant.store.dto.response;

import org.locationtech.jts.geom.Point;

import sweetgirl.croissant.store.entity.SavedStore;
import sweetgirl.croissant.store.entity.Store;

public record SavedStoreResponse(
		Long storeId,
		String storeName,
		String region,
		double latitude,
		double longitude,
		String thumbnailImageKey) {

	public static SavedStoreResponse from(SavedStore savedStore, String thumbnailImageKey) {
		Store store = savedStore.getStore();
		Point location = store.getLocation();
		return new SavedStoreResponse(
			store.getId(),
			store.getName(),
			store.getRegion2nd(),
			location.getY(),
			location.getX(),
			thumbnailImageKey);
	}
}
