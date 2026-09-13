package sweetgirl.croissant.recommendation.dto.response;

import java.util.List;

/** 상권 추천 응답 (설계 §6). fallback 은 에러가 아니라 결과다. 후보가 없으면 stores 가 비고 recommendationId 도 없다. */
public record RecommendationResponse(
		Long recommendationId,
		String title,
		RecommendationAreaSummary area,
		int totalDistanceM,
		boolean fallback,
		List<RecommendedStoreResponse> stores) {

	public static RecommendationResponse of(Long recommendationId, RecommendationResult result) {
		return new RecommendationResponse(recommendationId, result.title(), result.area(), result.totalDistanceM(),
			result.fallback(), result.stores());
	}
}
