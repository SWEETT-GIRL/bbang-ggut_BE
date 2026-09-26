package sweetgirl.croissant.store.dto.projection;

/**
 * 지도 영역 안 가게 한 줄 — 가까운 순으로 자른 가게 id. 영역 안 가게 수는 따로 센다(countStoresInBounds).
 */
public interface StoreInBoundsProjection {

	Long getStoreId();
}
