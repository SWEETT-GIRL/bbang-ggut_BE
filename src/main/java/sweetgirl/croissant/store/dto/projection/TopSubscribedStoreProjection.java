package sweetgirl.croissant.store.dto.projection;

public interface TopSubscribedStoreProjection {
	Long getId();

	String getName();

	String getRegion1st();

	String getRegion2nd();

	double getDistanceMeters();

	long getSubscribeCount();
}
