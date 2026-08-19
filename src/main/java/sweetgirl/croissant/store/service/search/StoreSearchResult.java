package sweetgirl.croissant.store.service.search;

import java.util.List;

/**
 * 검색 엔진의 한 페이지 결과.
 *
 * @param queryCovered 결과 중 최소 하나가 사용자가 입력한 말을 실제로 담고 있는지.
 *                     검색 품질 지표 분류에만 쓰이며 결과 노출에는 영향을 주지 않는다.
 */
public record StoreSearchResult(
		List<StoreSearchHit> hits,
		boolean hasNext,
		boolean queryCovered) {
	public static StoreSearchResult empty() {
		return new StoreSearchResult(List.of(), false, false);
	}
}
