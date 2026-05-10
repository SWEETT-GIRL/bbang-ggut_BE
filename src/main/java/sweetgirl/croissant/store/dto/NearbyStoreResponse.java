package sweetgirl.croissant.store.dto;

public record NearbyStoreResponse(
		Long id,
		String name,
		String district,
		String thumbnailUrl,
		int distanceMeters) {
	public static NearbyStoreResponse of(NearbyStoreProjection projection, String thumbnailUrl, int distanceMeters) {
		return new NearbyStoreResponse(
			projection.getId(),
			projection.getName(),
			projection.getDistrict(),
			thumbnailUrl,
			distanceMeters);
	}
}
