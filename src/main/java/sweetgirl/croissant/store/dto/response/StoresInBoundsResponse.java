package sweetgirl.croissant.store.dto.response;

import java.util.List;

/**
 * 지도 영역 안 가게.
 *
 * @param content 기준점에서 가까운 순. 줄 모양은 검색 결과와 같다 — 지도 목록이 한 모양으로 그린다.
 * @param total   자르기 전 영역 안 가게 수. content 보다 크면 앱이 "N곳 중 M곳"이라고 적는다.
 */
public record StoresInBoundsResponse(
		List<StoreSearchResponse> content,
		long total) {
}
