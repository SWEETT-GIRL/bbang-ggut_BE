package sweetgirl.croissant.recommendation.dto.response;

import java.util.List;

import sweetgirl.croissant.recommendation.enums.RecommendationContextState;

/** 빈 코스 카드의 상태 (설계 §6). areas 는 최대 3개, 첫 번째가 기본 칩. typeLabel 은 타입이 있을 때만. */
public record RecommendationContextResponse(
		RecommendationContextState state,
		boolean showAccuracyLink,
		List<RecommendationAreaResponse> areas,
		String typeLabel) {
}
