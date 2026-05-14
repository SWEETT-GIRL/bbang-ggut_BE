package sweetgirl.croissant.store.dto.projection;

public interface DistrictStoreProjection {
	Long getId();

	String getName();

	String getAddress();

	double getDistanceMeters();

	long getSubscribeCount();
}
