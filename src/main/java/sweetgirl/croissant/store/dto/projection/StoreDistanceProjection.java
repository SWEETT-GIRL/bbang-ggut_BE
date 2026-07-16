package sweetgirl.croissant.store.dto.projection;

// 특정 좌표 기준 가게까지의 거리(미터). 트렌드 랭킹에서 현재 위치 대비 거리 표시용.
public interface StoreDistanceProjection {
	Long getStoreId();

	double getDistanceMeters();
}
