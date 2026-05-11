package sweetgirl.croissant.store.repository;

// OS Geofence 등록 후보 조회 쿼리 결과 매핑용 Projection
public interface GeofenceCandidateProjection {
	Long getStoreId();

	String getStoreName();

	Double getLatitude();

	Double getLongitude();

	Double getDistanceMeters();
}
