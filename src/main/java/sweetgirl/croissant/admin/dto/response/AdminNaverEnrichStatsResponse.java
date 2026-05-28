package sweetgirl.croissant.admin.dto.response;

public record AdminNaverEnrichStatsResponse(
		boolean running,
		long matched,
		long pending,
		long failed,
		long total) {
}
