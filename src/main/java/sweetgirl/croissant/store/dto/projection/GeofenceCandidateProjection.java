package sweetgirl.croissant.store.dto.projection;

public interface GeofenceCandidateProjection {
	Long getStoreId();

	String getStoreName();

	Double getLatitude();

	Double getLongitude();

	Double getDistanceMeters();
}
