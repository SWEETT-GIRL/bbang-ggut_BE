package sweetgirl.croissant.breadranking.dto.response;

import java.util.List;

/**
 * 한 카테고리의 내 빵 순위.
 *
 * @param ranked   등수 순(공동 포함). 1등이 곧 이 카테고리의 내 한 표
 * @param unranked 담기만 하고 아직 안 정한 가게 — 최근에 담은 것부터
 */
public record MyBreadRankingResponse(
		BreadCategoryResponse category,
		List<MyBreadRankEntryResponse> ranked,
		List<MyBreadRankEntryResponse> unranked) {
}
