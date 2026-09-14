package sweetgirl.croissant.recommendation.dto.response;

import java.util.List;

/** 앵커 추천 한 페이지 (설계 §6 · FE PR #70). 같은 앵커의 모든 페이지가 같은 recommendationId 를 갖는다. */
public record AnchorRecommendationResponse(
		Long recommendationId,
		AnchorStoreSummary anchor,
		List<AnchorRecommendedStoreResponse> stores,
		boolean hasNext) {
}
