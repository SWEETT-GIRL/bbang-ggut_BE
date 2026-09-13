package sweetgirl.croissant.recommendation.dto.response;

/** 앵커 근처 가게 하나. 이유 문장은 없고 SQL 로 정해지는 배지(reasonTag)만 준다 (09-13). distanceM 은 앵커에서의 거리. */
public record AnchorRecommendedStoreResponse(
		Long storeId,
		String name,
		int distanceM,
		String reasonTag,
		double tasteFit,
		String thumbnailImageKey) {
}
