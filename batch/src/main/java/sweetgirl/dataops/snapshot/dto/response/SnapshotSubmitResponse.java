package sweetgirl.dataops.snapshot.dto.response;

/** PARTIAL completes store/hours and individually verified menu sources; HELD acknowledges durable evidence without downstream processing. */
public record SnapshotSubmitResponse(
		Long snapshotId,
		boolean changed,
		String contentHash,
		String captureId,
		String assessment,
		java.util.List<String> reasons) {
	public SnapshotSubmitResponse(Long snapshotId, boolean changed, String contentHash) {
		this(snapshotId, changed, contentHash, null, "READY", java.util.List.of());
	}
	public SnapshotSubmitResponse withCaptureId(String id) {
		return new SnapshotSubmitResponse(snapshotId, changed, contentHash, id, assessment, reasons);
	}
}
