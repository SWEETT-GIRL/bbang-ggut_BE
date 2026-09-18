package sweetgirl.croissant.recommendation.dto.response;

/**
 * 실험 입력이 된 가게 한 곳 (#662). 룰이 고른 순서 그대로다.
 *
 * @param ruleReason 지금 운영이 쓰는 문구 — LLM 문장이 이것을 이기는지가 판정 기준이다
 */
public record ReasonExperimentStore(
		Long storeId,
		String name,
		String region3rd,
		double tasteFit,
		String ruleReason) {
}
