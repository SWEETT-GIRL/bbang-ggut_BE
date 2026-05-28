package sweetgirl.croissant.admin.dto.response;

public record AdminNaverEnrichRunResponse(
		boolean matched,
		String naverPlaceId,
		String naverMatchedAt,
		long menuCount,
		int subprocessExitCode,
		String message) {
}
