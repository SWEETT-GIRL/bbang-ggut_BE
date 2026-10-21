package sweetgirl.croissant.searchmiss.service;

import java.util.Map;

import sweetgirl.croissant.searchmiss.enums.SearchMissStatus;
import sweetgirl.croissant.searchmiss.enums.SearchMissVerdict;

/**
 * 행 하나의 판정.
 *
 * @param resolvedStatus auto-apply 일 때 바꿀 처리 상태. 사람이 봐야 하면 null(PENDING 유지)
 * @param evidence 판정 단계·근거. {@code search_misses.ai_evidence}에 그대로 들어간다
 */
public record TriageDecision(
		SearchMissVerdict verdict,
		SearchMissStatus resolvedStatus,
		Long matchedStoreId,
		Map<String, Object> evidence) {
}
