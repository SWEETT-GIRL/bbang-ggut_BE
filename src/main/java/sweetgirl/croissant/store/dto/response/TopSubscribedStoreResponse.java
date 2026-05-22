package sweetgirl.croissant.store.dto.response;

import sweetgirl.croissant.store.dto.projection.TopSubscribedStoreProjection;

public record TopSubscribedStoreResponse(
		Long id,
		String name,
		String region,
		String storeThumbnailUrl,
		Long distanceMeters,
		long subscribeCount) {
	public static TopSubscribedStoreResponse of(TopSubscribedStoreProjection projection, String storeThumbnailUrl,
			Long distanceMeters) {
		return new TopSubscribedStoreResponse(
			projection.getId(),
			projection.getName(),
			joinRegion(projection.getRegion1st(), projection.getRegion2nd()),
			storeThumbnailUrl,
			distanceMeters,
			projection.getSubscribeCount());
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
