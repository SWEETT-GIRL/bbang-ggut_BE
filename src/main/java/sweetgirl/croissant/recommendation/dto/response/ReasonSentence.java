package sweetgirl.croissant.recommendation.dto.response;

/** 조합 하나가 가게 하나에 쓴 문장 (#662) */
public record ReasonSentence(
		Long storeId,
		String storeName,
		String reason) {
}
