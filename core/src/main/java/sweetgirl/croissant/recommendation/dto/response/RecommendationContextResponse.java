package sweetgirl.croissant.recommendation.dto.response;

import java.util.List;

import sweetgirl.croissant.recommendation.enums.RecommendationContextState;

/**
 * 빈 코스 카드의 상태 (설계 §6). areas 는 <b>그 시·도의 도장 칸 전부</b>이고 첫 번째가 기본 칩이다 — 개수를 자르지 않으므로
 * 몇 개를 보여줄지는 화면이 정한다. 시·도당 2~6개(시드 기준 전국 48칸)이고 한 번에 한 시·도만 나간다.
 * 유형 이름(typeLabel)은 주지 않는다 — 카드 제목은 FE 가 이름 없이 "{지역}에서 시작해볼까요?" 로 쓴다(#760).
 */
public record RecommendationContextResponse(
		RecommendationContextState state,
		boolean showAccuracyLink,
		List<RecommendationAreaResponse> areas) {
}
