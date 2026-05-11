package sweetgirl.croissant.store.dto;

public record TopSubscribedStoreResponse(
		Long id,
		String name,
		String district,
		String storeThumbnailUrl,
		int distanceMeters,
		long subscribeCount) {
	public static TopSubscribedStoreResponse of(TopSubscribedStoreProjection projection, String storeThumbnailUrl,
			int distanceMeters) {
		return new TopSubscribedStoreResponse(
			projection.getId(),
			projection.getName(),
			projection.getDistrict(),
			storeThumbnailUrl,
			distanceMeters,
			projection.getSubscribeCount());
	}
}
