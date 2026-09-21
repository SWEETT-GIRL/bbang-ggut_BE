package sweetgirl.croissant.recommendation.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 실험 입력이 된 가게 한 곳 (#662). 룰이 고른 순서 그대로다.
 *
 * @param tasteFit 취향 일치도. 블라인드 모드에서는 빠진다 — 숫자가 높은 가게의 문장을 더 좋게 보게 만든다
 * @param ruleReason 지금 운영이 쓰는 문구. LLM 문장이 이것을 이기는지가 판정 기준이다
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReasonExperimentStore(
		Long storeId,
		String name,
		String region3rd,
		Double tasteFit,
		String ruleReason) {

	/** 블라인드 1차용 — 판단을 기울일 수 있는 점수를 뺀다. 룰 문구는 2차 비교에 쓰므로 남긴다 */
	public ReasonExperimentStore withoutScore() {
		return new ReasonExperimentStore(storeId, name, region3rd, null, ruleReason);
	}
}
