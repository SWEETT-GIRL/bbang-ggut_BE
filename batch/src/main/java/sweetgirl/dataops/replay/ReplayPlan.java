package sweetgirl.dataops.replay;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/** Returned only by a trusted approval adapter; constructing this record is not authorization. */
public record ReplayPlan(UUID operationId, String manifestDigest, String releaseId, String artifactDigest,
		String parserDigest, String profileDigest, String contractVersion, String policyDigest, Instant expiresAt,
		List<Target> targets, List<String> canaryCaptureIds) {
	public ReplayPlan {
		targets = List.copyOf(targets);
		canaryCaptureIds = List.copyOf(canaryCaptureIds);
		if (operationId == null || expiresAt == null || targets.isEmpty() || targets.size() > 1000
			|| canaryCaptureIds.isEmpty() || canaryCaptureIds.size() > 20
			|| releaseId == null || releaseId.isBlank() || contractVersion == null || contractVersion.isBlank())
			throw new IllegalArgumentException("Invalid approved replay scope");
		for (String hash : List.of(manifestDigest, artifactDigest, parserDigest, profileDigest, policyDigest)) hash(hash);
		var ids = new HashSet<String>();
		for (Target target : targets) if (!ids.add(target.captureId())) throw new IllegalArgumentException("Duplicate replay target");
		if (new HashSet<>(canaryCaptureIds).size() != canaryCaptureIds.size() || !ids.containsAll(canaryCaptureIds))
			throw new IllegalArgumentException("Canary is outside approved targets");
	}
	public record Target(String captureId, String payloadDigest, long storeId, String externalId,
			Instant observedAt, Long attemptSeq, String expectedParsedDigest) {
		public Target {
			if (captureId == null || captureId.isBlank() || storeId <= 0 || externalId == null || externalId.isBlank()
				|| observedAt == null || observedAt.isBefore(Instant.EPOCH) || (attemptSeq != null && attemptSeq < 0))
				throw new IllegalArgumentException("Incomplete replay target");
			hash(payloadDigest); hash(expectedParsedDigest);
		}
	}
	private static void hash(String value) {
		if (value == null || !value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Digest required");
	}
}
