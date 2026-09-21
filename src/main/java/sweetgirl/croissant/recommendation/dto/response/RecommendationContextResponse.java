package sweetgirl.croissant.recommendation.dto.response;

import java.util.List;

import sweetgirl.croissant.recommendation.enums.RecommendationContextState;

/**
 * 빈 코스 카드의 상태 (설계 §6). areas 는 최대 3개, 첫 번째가 기본 칩. 유형 이름(typeLabel)은 주지 않는다 — 카드 제목은 FE 가
 * 이름 없이 "{지역}에서 시작해볼까요?" 로 쓴다(#760).
 */
public record RecommendationContextResponse(
		RecommendationContextState state,
		boolean showAccuracyLink,
		List<RecommendationAreaResponse> areas) {
}
