package sweetgirl.croissant.recommendation.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** 결과에 붙는 상권 이름표. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RecommendationAreaSummary(Long areaId, String name) {
}
