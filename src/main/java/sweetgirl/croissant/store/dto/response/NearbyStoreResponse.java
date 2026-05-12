package sweetgirl.croissant.store.dto.response;

import sweetgirl.croissant.store.dto.projection.NearbyStoreProjection;

public record NearbyStoreResponse(
		Long id,
		String name,
		String district,
		String storeThumbnailUrl,
		int distanceMeters) {
	public static NearbyStoreResponse of(NearbyStoreProjection projection, String storeThumbnailUrl,
			int distanceMeters) {
		return new NearbyStoreResponse(
			projection.getId(),
			projection.getName(),
			projection.getDistrict(),
			storeThumbnailUrl,
			distanceMeters);
	}
}
