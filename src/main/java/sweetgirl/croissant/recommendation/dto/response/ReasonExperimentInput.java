package sweetgirl.croissant.recommendation.dto.response;

import java.util.List;

/**
 * 어떤 판으로 돌렸는지 (#662). 조합별 결과를 읽기 전에 입력이 같은지부터 확인하려는 것.
 *
 * @param tasteFallback 취향 후보가 모자라 취향 항을 빼고 뽑았는지 — 이때 룰 문구는 전부 시그니처가 된다
 */
public record ReasonExperimentInput(
		Long areaId,
		String areaName,
		String typeCode,
		boolean hasAxis,
		boolean tasteFallback,
		List<ReasonExperimentStore> stores) {
}
