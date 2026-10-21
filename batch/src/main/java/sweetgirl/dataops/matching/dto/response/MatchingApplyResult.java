package sweetgirl.dataops.matching.dto.response;

import java.util.List;

/** 매칭 배치 반영 결과. skippedAlreadyMatched: 후보 확정 후 팀이 그새 매칭해 건너뛴 수 (선점 방지) */
public record MatchingApplyResult(
		int requested,
		int applied,
		int skippedAlreadyMatched,
		int failed,
		List<Long> failedStoreIds) {
}
