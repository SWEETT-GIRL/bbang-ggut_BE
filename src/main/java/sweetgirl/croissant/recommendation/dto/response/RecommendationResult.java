package sweetgirl.croissant.recommendation.dto.response;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 상권 추천의 최종본 — recommendation.result 에 JSON 으로 저장되는 것 그대로다. 재사용 때 이 모양으로 다시 읽어 응답을 만든다.
 * recommendationId 는 저장 뒤에 생기므로 여기 없다. storeCount 는 요청한 가게 수다 — 후보가 모자라 stores 가 그보다 짧아도
 * 같은 요청이면 재사용해야 하므로 stores.size() 가 아니라 이 값과 비교한다.
 * 모르는 필드는 무시한다: #661·#662 가 결과 모양을 바꿔도 옛 행을 읽을 수 있어야 한다(JacksonConfig 의 ObjectMapper 는 기본이 실패).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RecommendationResult(
		String title,
		RecommendationAreaSummary area,
		int totalDistanceM,
		boolean fallback,
		int storeCount,
		List<RecommendedStoreResponse> stores,
		boolean lastBatch) {

	/** lastBatch 가 없던 행(#661 이전)과 테스트 픽스처 — 더 있을 수 있다고 본다. */
	public RecommendationResult(String title, RecommendationAreaSummary area, int totalDistanceM, boolean fallback,
			int storeCount, List<RecommendedStoreResponse> stores) {
		this(title, area, totalDistanceM, fallback, storeCount, stores, false);
	}
}
