package sweetgirl.croissant.store.dto.projection;

public interface RegionBySubscribeProjection {
	Long getId();

	String getName();

	String getAddress();

	long getSubscribeCount();
}
