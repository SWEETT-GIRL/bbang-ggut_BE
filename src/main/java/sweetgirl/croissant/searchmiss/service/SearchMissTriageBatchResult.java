package sweetgirl.croissant.searchmiss.service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import sweetgirl.croissant.searchmiss.enums.SearchMissVerdict;

/**
 * 배치 한 회차의 결과.
 *
 * @param total 꺼낸 행 수
 * @param items 판정을 마친 행
 * @param failures 외부 호출 실패 등으로 건너뛴 행 — 다음 날 배치에서 다시 집힌다
 * @param stoppedReason 한도 초과 등으로 회차를 도중에 멈췄으면 그 사유. 끝까지 돌았으면 null
 */
public record SearchMissTriageBatchResult(
		int total,
		List<SearchMissTriageItem> items,
		List<SearchMissTriageFailure> failures,
		String stoppedReason) {

	public SearchMissTriageBatchResult {
		items = List.copyOf(items);
		failures = List.copyOf(failures);
	}

	public Map<SearchMissVerdict, Integer> verdicts() {
		Map<SearchMissVerdict, Integer> counts = new EnumMap<>(SearchMissVerdict.class);
		items.forEach(item -> counts.merge(item.decision().verdict(), 1, Integer::sum));
		return counts;
	}

	public int failed() {
		return failures.size();
	}

	/** 판정도 실패도 안 하고 남은 행 — 회차를 도중에 멈췄을 때만 생긴다 */
	public int skipped() {
		return total - items.size() - failures.size();
	}

	public boolean isStopped() {
		return stoppedReason != null;
	}
}
