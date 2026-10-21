package sweetgirl.dataops.replay;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import sweetgirl.dataops.reconcile.service.ReconcileService;

public interface ReplayJournal {
	void begin(ReplayPlan plan);
	void lock(UUID operationId, String manifestDigest);
	Optional<Capture> capture(String captureId);
	boolean latest(Capture capture);
	Optional<ItemReceipt> receipt(UUID operationId, String captureId, String phase);
	void append(UUID operationId, String phase, Capture capture, ItemReceipt receipt,
		List<ReconcileService.ReplayProposal> proposals);
	boolean canaryPassed(UUID operationId);
	void complete(UUID operationId, String phase, boolean passed);

	record Capture(String captureId, long storeId, String source, String externalId, OffsetDateTime observedAt,
		OffsetDateTime receivedAt, Long attemptSeq, String payload, String payloadDigest, String originalParserDigest,
		String originalContractVersion) {}
	record ItemReceipt(String captureId, String status, String reason, String parsedDigest,
		String parsedJson, List<Long> createdProposalIds, List<Long> reusedProposalIds) {
		public ItemReceipt {
			createdProposalIds = List.copyOf(createdProposalIds);
			reusedProposalIds = List.copyOf(reusedProposalIds);
		}
	}
}
