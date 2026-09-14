package sweetgirl.croissant.recommendation.dto.response;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** 앵커 추천의 순위 전체 — recommendation.result 에 저장되는 것. 페이지는 이 목록을 잘라 만든다. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AnchorRecommendationResult(
		AnchorStoreSummary anchor,
		List<AnchorRecommendedStoreResponse> stores) {
}
