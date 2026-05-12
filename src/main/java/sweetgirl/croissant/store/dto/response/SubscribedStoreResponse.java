package sweetgirl.croissant.store.dto.response;

import sweetgirl.croissant.store.entity.SubscribedStore;
import sweetgirl.croissant.store.enums.StoreStatus;

public record SubscribedStoreResponse(
		Long storeId,
		String storeName,
		String address,
		StoreStatus status,
		String imageUrl) {

	public static SubscribedStoreResponse from(SubscribedStore subscribedStore, String imageUrl) {
		return new SubscribedStoreResponse(
			subscribedStore.getStore().getId(),
			subscribedStore.getStore().getName(),
			subscribedStore.getStore().getAddress(),
			subscribedStore.getStore().getStatus(),
			imageUrl);
	}
}
