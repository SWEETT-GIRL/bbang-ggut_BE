package sweetgirl.croissant.external.opensearch.dto;

import java.util.List;

/**
 * @param queryCovered 결과 중 최소 하나가 사용자가 입력한 말을 실제로 담고 있는지.
 *                     검색 품질 지표 분류에만 쓰이며 결과 노출에는 영향을 주지 않는다.
 */
public record OpenSearchStoreSearchResult(
		List<OpenSearchStoreHit> hits,
		boolean hasNext,
		boolean queryCovered) {

	public static OpenSearchStoreSearchResult empty() {
		return new OpenSearchStoreSearchResult(List.of(), false, false);
	}
}
