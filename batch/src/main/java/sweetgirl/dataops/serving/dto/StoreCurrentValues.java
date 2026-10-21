package sweetgirl.dataops.serving.dto;

public record StoreCurrentValues(Long storeId, String name, String address, String phone, String status,
		String instagramHandle, Double longitude, Double latitude) {
}
