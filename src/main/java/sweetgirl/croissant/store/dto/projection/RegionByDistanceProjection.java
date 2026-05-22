package sweetgirl.croissant.store.dto.projection;

public interface RegionByDistanceProjection {
	Long getId();

	String getName();

	String getAddress();

	double getDistanceMeters();
}
