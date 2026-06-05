package sweetgirl.croissant.admin.dto.response;

public record AdminNaverEnrichStatsResponse(
		boolean running,
		long matched,
		long pending,
		long failed,
		long total,
		// 진행률 표시용 — 일괄 작업이 돌고 있을 때만 채워짐. 그 외엔 null/0.
		String runningMode,
		String runningPartition,
		String startedAt,
		long startedQueueCount) {
}
