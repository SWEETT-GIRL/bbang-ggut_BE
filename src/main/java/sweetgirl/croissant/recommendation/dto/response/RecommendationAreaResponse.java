package sweetgirl.croissant.recommendation.dto.response;

import sweetgirl.croissant.recommendation.enums.RecommendationAreaReason;

/** 상권 칩 하나. */
public record RecommendationAreaResponse(
		Long areaId,
		String name,
		RecommendationAreaReason reason,
		int storeCount) {
}
