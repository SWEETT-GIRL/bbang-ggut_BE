package sweetgirl.croissant.store.dto.projection;

/** 다음 빵집 제안의 근처 후보 한 줄. */
public interface NextStoreCandidateProjection {
	Long getId();

	String getName();

	String getAddress();

	Double getLatitude();

	Double getLongitude();

	Double getDistanceMeters();

	Long getPublicRecordCount();
}
