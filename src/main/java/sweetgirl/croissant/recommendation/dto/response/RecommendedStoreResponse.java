package sweetgirl.croissant.recommendation.dto.response;

/** 코스의 가게 하나. 순서가 곧 방문 순서이고 distanceToNextM 은 마지막 가게에서 null. tasteFit 은 화면엔 없고 이벤트 프로퍼티로 나간다. */
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
}
