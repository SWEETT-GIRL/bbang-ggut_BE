package sweetgirl.croissant.recommendation.dto.response;

import java.util.List;

/**
 * 상권 추천의 최종본 — recommendation.result 에 JSON 으로 저장되는 것 그대로다. 재사용 때 이 모양으로 다시 읽어 응답을 만든다.
 * recommendationId 는 저장 뒤에 생기므로 여기 없다.
 */
public record RecommendationResult(
		String title,
		RecommendationAreaSummary area,
		int totalDistanceM,
		boolean fallback,
		List<RecommendedStoreResponse> stores) {
}
