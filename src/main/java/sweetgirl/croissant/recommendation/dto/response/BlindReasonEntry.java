package sweetgirl.croissant.recommendation.dto.response;

/**
 * 블라인드 비교용 한 줄 (#662). 어떤 조합이 썼는지는 빠져 있고, 라벨만 남는다.
 *
 * <p><b>여기에는 LLM 문장만 들어간다.</b> 룰 문구를 같이 섞어 봤자 가려지지 않는다 — 룰은
 * {@code RecommendationMessage} 의 고정 문장 셋 중 하나라 여러 가게에 똑같이 반복되고, "가게마다 같은 말이
 * 나오는 보기"를 고르면 그게 룰이다. 그래서 판정을 두 번에 나눈다.
 *
 * <ol>
 *   <li>1차(블라인드): 가게마다 LLM 문장들을 섞어 놓고 제일 나은 하나를 고른다. 어느 조합인지는 답표로 푼다.</li>
 *   <li>2차(드러내고): 1차 승자와 그 가게의 {@code input.stores[].ruleReason} 을 나란히 놓고 이겼는지 본다.</li>
 * </ol>
 *
 * <p>2차는 블라인드가 아니다. 가릴 수 없는 것을 가린 척하지 않는 쪽을 골랐다.
 */
public record BlindReasonEntry(
		String label,
		Long storeId,
		String storeName,
		String reason) {
}
