package sweetgirl.croissant.store.dto.response;

import sweetgirl.croissant.store.dto.projection.NearbyStoreProjection;

public record NearbyStoreResponse(
		Long id,
		String name,
		String region,
		String storeThumbnailUrl,
		int distanceMeters) {
	public static NearbyStoreResponse of(NearbyStoreProjection projection, String storeThumbnailUrl,
			int distanceMeters) {
		return new NearbyStoreResponse(
			projection.getId(),
			projection.getName(),
			joinRegion(projection.getRegion1st(), projection.getRegion2nd()),
			storeThumbnailUrl,
			distanceMeters);
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
