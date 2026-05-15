package sweetgirl.croissant.store.dto.projection;

public interface TopSubscribedStoreProjection {
	Long getId();

	String getName();

	String getDistrict();

	double getDistanceMeters();

	long getSubscribeCount();
}
