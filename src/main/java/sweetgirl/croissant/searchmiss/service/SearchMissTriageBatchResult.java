package sweetgirl.croissant.searchmiss.service;

import java.util.Map;

import sweetgirl.croissant.searchmiss.enums.SearchMissVerdict;

/**
 * @param total 꺼낸 행 수
 * @param verdicts 판정별 건수
 * @param failed 외부 호출 실패 등으로 건너뛴 수 — 다음 날 배치에서 다시 집힌다
 */
public record SearchMissTriageBatchResult(
		int total,
		Map<SearchMissVerdict, Integer> verdicts,
		int failed) {
}
