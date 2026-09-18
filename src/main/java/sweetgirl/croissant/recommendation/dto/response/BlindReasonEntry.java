package sweetgirl.croissant.recommendation.dto.response;

/**
 * 블라인드 비교용 한 줄 (#662). 어떤 조합이 썼는지는 빠져 있고, 라벨만 남는다.
 *
 * <p>룰 문구도 같은 모양으로 섞인다 — <b>룰을 못 이기면 런타임 LLM 은 접은 채로 둔다</b>는 것이 09-03 비용
 * 계산기가 적어 둔 뒤집는 조건이라, 기준선이 판에 같이 올라야 판정이 성립한다.
 */
public record BlindReasonEntry(
		String label,
		Long storeId,
		String storeName,
		String reason) {
}
