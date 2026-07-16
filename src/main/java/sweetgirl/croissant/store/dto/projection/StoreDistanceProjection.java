package sweetgirl.croissant.store.dto.projection;

/** 가게별 거리 일괄 조회용 — 트렌드 홈에서 사용자 위치 기준 거리 표시에 사용 */
public interface StoreDistanceProjection {

	Long getId();

	double getDistanceMeters();
}
