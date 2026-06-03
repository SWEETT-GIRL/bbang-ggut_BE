package sweetgirl.croissant.admin.dto.response;

public record AdminNaverEnrichRunResponse(
		boolean matched,
		String naverPlaceId,
		String naverMatchedAt,
		long menuCount,
		long photoCount,
		int subprocessExitCode,
		String message) {
}
