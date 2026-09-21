package sweetgirl.croissant.recommendation.dto.response;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 상권 추천 응답 (설계 §6). fallback 은 에러가 아니라 결과다. 후보가 없으면 stores 가 비고 recommendationId 도 없다.
 * lastBatch · nextArea · anchorStoreId 는 다시 추천 계약(#661): 뒤 둘은 stores 가 비었을 때만 채운다.
 */
public record RecommendationResponse(
		Long recommendationId,
		String title,
		RecommendationAreaSummary area,
		int totalDistanceM,
		boolean fallback,
		List<RecommendedStoreResponse> stores,
		@Schema(description = "이 묶음이 마지막 — 다시 받기를 눌러도 더 없다") boolean lastBatch,
		@Schema(description = "후보가 바닥났을 때 다음 동네 제안. stores 가 비었을 때만") NextAreaResponse nextArea,
		@Schema(description = "후보가 바닥났을 때 앵커 추천으로 넘길 코스 가게. stores 가 비었을 때만") Long anchorStoreId) {

	public RecommendationResponse(Long recommendationId, String title, RecommendationAreaSummary area,
			int totalDistanceM, boolean fallback, List<RecommendedStoreResponse> stores) {
		this(recommendationId, title, area, totalDistanceM, fallback, stores, false, null, null);
	}

	public static RecommendationResponse of(Long recommendationId, RecommendationResult result) {
		return of(recommendationId, result, null, null);
	}

	public static RecommendationResponse of(Long recommendationId, RecommendationResult result,
			NextAreaResponse nextArea, Long anchorStoreId) {
		return new RecommendationResponse(recommendationId, result.title(), result.area(), result.totalDistanceM(),
			result.fallback(), result.stores(), result.lastBatch(), nextArea, anchorStoreId);
	}
}
