package sweetgirl.croissant.store.dto.projection;

/**
 * 지도 영역 안 가게 한 줄 — 가까운 순으로 자른 가게 id 와, 자르기 전 영역 안 가게 수.
 */
public interface StoreInBoundsProjection {

	Long getStoreId();

	/** LIMIT 전 영역 안 전체 가게 수. 모든 줄에 같은 값이 실린다(count(*) OVER ()) */
	Long getTotal();
}
