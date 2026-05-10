package sweetgirl.croissant.store.dto;

public interface NearbyStoreProjection {
	Long getId();

	String getName();

	String getDistrict();

	double getDistanceMeters();
}
