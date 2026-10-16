package sweetgirl.croissant.store.service.search;

import java.util.List;

/**
 * 기록을 남기지 않은 검색 한 번의 판정과 결과 가게.
 *
 * @param storeIds 관련도 순 가게 id
 * @param hasMore 요청한 수보다 결과가 더 있다 — 체인 지점이 너무 많다는 신호로 쓴다
 */
public record StoreSearchProbe(
		SearchOutcome outcome,
		List<Long> storeIds,
		boolean hasMore) {

	public boolean isHit() {
		return outcome == SearchOutcome.HIT;
	}
}
