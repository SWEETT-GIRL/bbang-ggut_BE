package sweetgirl.croissant.store.dto.response;

public record BusinessHourSyncResponse(
		int total,
		int success,
		int skipped,
		int failed) {
}
