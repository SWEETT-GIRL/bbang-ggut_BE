package sweetgirl.dataops.sync.dto.response;

public record SeedResultResponse(
		long matchedStores,
		long created,
		long skipped,
		long provenanceBackfilled) {
}
