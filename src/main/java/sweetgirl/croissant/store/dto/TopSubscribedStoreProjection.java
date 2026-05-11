package sweetgirl.croissant.store.dto;

public interface TopSubscribedStoreProjection {
	Long getId();

	String getName();

	String getDistrict();

	double getDistanceMeters();

	long getSubscribeCount();
}
