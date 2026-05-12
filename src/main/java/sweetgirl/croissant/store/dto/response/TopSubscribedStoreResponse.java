package sweetgirl.croissant.store.dto.response;

import sweetgirl.croissant.store.dto.projection.TopSubscribedStoreProjection;

public record TopSubscribedStoreResponse(
		Long id,
		String name,
		String district,
		String storeThumbnailUrl,
		Long distanceMeters,
		long subscribeCount) {
	public static TopSubscribedStoreResponse of(TopSubscribedStoreProjection projection, String storeThumbnailUrl,
			Long distanceMeters) {
		return new TopSubscribedStoreResponse(
			projection.getId(),
			projection.getName(),
			projection.getDistrict(),
			storeThumbnailUrl,
			distanceMeters,
			projection.getSubscribeCount());
	}
}
