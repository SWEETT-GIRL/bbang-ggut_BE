package sweetgirl.croissant.recommendation.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** 앵커(방금 담은 가게) 이름표. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AnchorStoreSummary(Long storeId, String name) {
}
