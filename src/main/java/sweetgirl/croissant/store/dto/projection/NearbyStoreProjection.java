package sweetgirl.croissant.store.dto.projection;

public interface NearbyStoreProjection {
	Long getId();

	String getName();

	String getRegion1st();

	String getRegion2nd();

	Double getLatitude();

	Double getLongitude();

	double getDistanceMeters();
}
