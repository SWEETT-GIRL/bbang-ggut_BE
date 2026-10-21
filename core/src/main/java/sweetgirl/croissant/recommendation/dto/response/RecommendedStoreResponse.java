package sweetgirl.croissant.recommendation.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** 코스의 가게 하나. 순서가 곧 방문 순서이고 distanceToNextM 은 마지막 가게에서 null. tasteFit 은 화면엔 없고 이벤트 프로퍼티로 나간다. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RecommendedStoreResponse(
		Long storeId,
		String name,
		String region3rd,
		String reason,
		double tasteFit,
		Integer distanceToNextM,
		double latitude,
		double longitude,
		String thumbnailImageKey) {

	/** 이유 문장만 갈아끼운 사본 (#764) — 나머지는 룰이 정한 그대로여야 한다. 가게도 순서도 바뀌지 않는다 */
	public RecommendedStoreResponse withReason(String reason) {
		return new RecommendedStoreResponse(storeId, name, region3rd, reason, tasteFit, distanceToNextM,
			latitude, longitude, thumbnailImageKey);
	}
}
