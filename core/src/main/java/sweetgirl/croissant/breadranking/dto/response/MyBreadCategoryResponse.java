package sweetgirl.croissant.breadranking.dto.response;

import java.util.List;

/**
 * 내 빵 순위 탭 하나.
 *
 * @param topStores 이 카테고리의 내 1등(공동이면 여러 개). 아직 없으면 빈 목록 — 마이 "내 빵 순위" 줄은 첫 탭의 첫 가게를 쓴다
 */
public record MyBreadCategoryResponse(BreadCategoryResponse category, int sortOrder,
		List<StoreSummaryResponse> topStores) {
}
