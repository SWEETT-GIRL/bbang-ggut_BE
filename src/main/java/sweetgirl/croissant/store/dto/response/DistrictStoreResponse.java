package sweetgirl.croissant.store.dto.response;

public record DistrictStoreResponse(
		Long id,
		String name,
		String address,
		String thumbnailUrl) {

	public static DistrictStoreResponse of(Long id, String name, String address, String thumbnailUrl) {
		return new DistrictStoreResponse(id, name, address, thumbnailUrl);
	}
}
