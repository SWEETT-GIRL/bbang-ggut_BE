package sweetgirl.croissant.store.dto.projection;

public interface NearbyStoreProjection {
	Long getId();

	String getName();

	String getDistrict();

	double getDistanceMeters();
}
