package sweetgirl.croissant.store.dto.projection;

public interface NearbyStoreProjection {
	Long getId();

	String getName();

	String getRegion1st();

	String getRegion2nd();

	Double getLatitude();

	Double getLongitude();

	/** store.category 문자열(StoreCategory 이름). 아직 못 받은 가게는 null */
	String getCategory();

	double getDistanceMeters();
}
