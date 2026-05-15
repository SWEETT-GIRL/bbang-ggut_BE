package sweetgirl.croissant.store.dto.response;

import sweetgirl.croissant.store.dto.projection.NearbyStoreProjection;
import sweetgirl.croissant.store.enums.District;

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
			District.toCityName(projection.getDistrict()),
			storeThumbnailUrl,
			distanceMeters);
	}
}
