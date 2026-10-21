package sweetgirl.croissant.recommendation.dto.response;

import sweetgirl.croissant.recommendation.enums.ReasonSource;

/**
 * 조합 하나가 가게 하나에 쓴 문장 (#662).
 *
 * @param source 이 줄이 룰인지 LLM 인지 (#807 리뷰) — 묶음 단위 {@code fallback} 으로는 못 가린다.
 *               한 묶음에 룰과 LLM 이 섞이기 때문이다: LLM 이 일부 가게만 답하거나, 일부 줄이 길이 상한을 넘으면
 *               그 줄만 룰로 남는다. 옛 행이라 출처를 모르면 {@code UNKNOWN} 이다 — LLM 으로 뭉뚱그리면
 *               성공률이 부풀고 되짚을 수 없다.
 */
public record ReasonSentence(
		Long storeId,
		String storeName,
		String reason,
		ReasonSource source) {
}
