package sweetgirl.dataops.replay;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import sweetgirl.dataops.reconcile.service.ReconcileService;
import sweetgirl.dataops.snapshot.concurrency.StoreObservationBarrier;
import sweetgirl.dataops.snapshot.dto.request.SnapshotSubmitRequest;
import sweetgirl.dataops.snapshot.parser.NaverPlaceParser;
import sweetgirl.dataops.snapshot.service.ResponseDiagnostics;
import sweetgirl.dataops.snapshot.service.ResponseDigests;
import sweetgirl.dataops.sync.enums.SyncSource;
import sweetgirl.dataops.sync.repository.StoreSyncStateRepository;
import tools.jackson.databind.ObjectMapper;

/** Internal, approval-bound replay; deliberately has no controller, scheduler or serving writer. */
@Service
public class CaptureReplayService {
	private final ObjectProvider<VerifiedReplayAuthority> authorities;
	private final ReplayJournal journal;
	private final StoreObservationBarrier barrier;
	private final StoreSyncStateRepository syncStates;
	private final ResponseDiagnostics diagnostics;
	private final NaverPlaceParser parser;
	private final ReconcileService reconcile;
	private final ObjectMapper mapper;
	private final TransactionTemplate transaction;

	public CaptureReplayService(ObjectProvider<VerifiedReplayAuthority> authorities, ReplayJournal journal,
			StoreObservationBarrier barrier, StoreSyncStateRepository syncStates, ResponseDiagnostics diagnostics,
			NaverPlaceParser parser, ReconcileService reconcile, ObjectMapper mapper, PlatformTransactionManager manager) {
		this.authorities = authorities; this.journal = journal; this.barrier = barrier; this.syncStates = syncStates;
		this.diagnostics = diagnostics; this.parser = parser; this.reconcile = reconcile; this.mapper = mapper;
		this.transaction = new TransactionTemplate(manager);
		this.transaction.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	public record PhaseReceipt(UUID operationId, String manifestDigest, String phase, String releaseId,
		String artifactDigest, String parserDigest, String profileDigest, String targetDigest,
		List<ReplayJournal.ItemReceipt> items, int operatingWrites, boolean passed) {
		public PhaseReceipt { items = List.copyOf(items); }
	}

	public PhaseReceipt canary(UUID operationId, String manifestDigest) { return execute(operationId, manifestDigest, "CANARY"); }
	public PhaseReceipt replay(UUID operationId, String manifestDigest) { return execute(operationId, manifestDigest, "REPLAY"); }

	private PhaseReceipt execute(UUID operationId, String manifestDigest, String phase) {
		ReplayPlan plan = authorized(operationId, manifestDigest);
		transaction.executeWithoutResult(status -> journal.begin(plan));
		boolean canary = "CANARY".equals(phase);
		if (!canary && !journal.canaryPassed(operationId)) throw new IllegalStateException("CANARY_NOT_PASSED");
		var targets = plan.targets().stream().filter(target -> !canary || plan.canaryCaptureIds().contains(target.captureId())).toList();
		var receipts = new ArrayList<ReplayJournal.ItemReceipt>();
		for (ReplayPlan.Target target : targets) {
			// A scope revoked/expired or runtime changed during a long batch stops further items.
			ReplayPlan current = authorized(operationId, manifestDigest);
			if (!plan.equals(current)) throw new IllegalStateException("REPLAY_PLAN_CHANGED");
			ReplayJournal.ItemReceipt receipt = transaction.execute(status -> item(plan, target, phase));
			receipts.add(Objects.requireNonNull(receipt));
			// A failed canary must never expand. Replay stops on the first unhandled item as well.
			if (!successful(receipt)) break;
		}
		boolean passed = receipts.size() == targets.size() && receipts.stream().allMatch(this::successful);
		transaction.executeWithoutResult(status -> { journal.lock(operationId, manifestDigest); journal.complete(operationId, phase, passed); });
		return new PhaseReceipt(operationId, manifestDigest, phase, plan.releaseId(), plan.artifactDigest(),
			plan.parserDigest(), plan.profileDigest(), digest(targets), receipts, 0, passed);
	}

	private ReplayPlan authorized(UUID operationId, String manifestDigest) {
		VerifiedReplayAuthority authority = authorities.getIfAvailable();
		if (authority == null) throw new IllegalStateException("AUTHENTICATED_REPLAY_APPROVAL_REQUIRED");
		ReplayPlan plan = authority.require(operationId, manifestDigest);
		if (plan == null || !operationId.equals(plan.operationId()) || !manifestDigest.equals(plan.manifestDigest())
			|| !plan.expiresAt().isAfter(Instant.now())) throw new IllegalStateException("REPLAY_APPROVAL_STALE");
		var runtime = authority.currentRuntime();
		if (runtime == null || !plan.releaseId().equals(runtime.releaseId()) || !plan.artifactDigest().equals(runtime.artifactDigest())
			|| !plan.parserDigest().equals(diagnostics.runtimeParserDigest())
			|| !plan.profileDigest().equals(diagnostics.structureProfile().digest())
			|| !plan.profileDigest().equals(parser.structureProfile().digest())
			|| !plan.contractVersion().equals(ResponseDiagnostics.CONTRACT)
			|| !plan.policyDigest().equals(reconcile.replayPolicyDigest()))
			throw new IllegalStateException("REPLAY_RUNTIME_MISMATCH");
		return plan;
	}

	private ReplayJournal.ItemReceipt item(ReplayPlan plan, ReplayPlan.Target target, String phase) {
		journal.lock(plan.operationId(), plan.manifestDigest());
		var previous = journal.receipt(plan.operationId(), target.captureId(), phase);
		if (previous.isPresent()) return previous.get();
		ReplayJournal.Capture capture = journal.capture(target.captureId())
			.orElseThrow(() -> new IllegalStateException("APPROVED_CAPTURE_MISSING"));
		barrier.lockObservation(capture.storeId());
		var state = syncStates.findForSnapshotUpdate(capture.storeId(), SyncSource.NAVER).orElse(null);
		String hold = null;
		if (!"NAVER".equals(capture.source()) || capture.observedAt() == null || capture.receivedAt() == null
			|| capture.observedAt().isAfter(capture.receivedAt().plusMinutes(5))
			|| !target.payloadDigest().equals(capture.payloadDigest())
			|| !capture.payloadDigest().equals(ResponseDigests.sha256(capture.payload()))
			|| target.storeId() != capture.storeId() || !target.externalId().equals(capture.externalId())
			|| !target.observedAt().equals(capture.observedAt().toInstant())
			|| !Objects.equals(target.attemptSeq(), capture.attemptSeq())) hold = "CAPTURE_PROVENANCE_MISMATCH";
		else if (state == null || !capture.externalId().equals(state.getExternalId())) hold = "CAPTURE_IDENTITY_CHANGED";
		else if ((capture.attemptSeq() != null && capture.attemptSeq() < state.getAttemptSeq()) || !journal.latest(capture))
			hold = "STALE_CAPTURE";
		if (hold != null) return append(plan, phase, capture, new ReplayJournal.ItemReceipt(capture.captureId(),
			"STALE_CAPTURE".equals(hold) ? "STALE" : "HELD", hold, null, null, List.of(), List.of()), List.of());
		var payload = mapper.readTree(capture.payload());
		var request = new SnapshotSubmitRequest(capture.storeId(), SyncSource.NAVER, payload, null, capture.captureId(),
			capture.externalId(), capture.observedAt(), capture.attemptSeq());
		var diagnostic = diagnostics.inspect(request);
		if (diagnostic.held() || !diagnostic.targetVerified()) return append(plan, phase, capture,
			new ReplayJournal.ItemReceipt(capture.captureId(), "HELD", "REPLAY_DIAGNOSTIC_HELD", null, null, List.of(), List.of()), List.of());
		var parsed = ResponseDiagnostics.parsePreservingInput(parser, payload);
		String parsedJson = ResponseDigests.canonical(mapper.valueToTree(parsed), mapper);
		String parsedDigest = ResponseDigests.sha256(parsedJson);
		if (!target.expectedParsedDigest().equals(parsedDigest)) return append(plan, phase, capture,
			new ReplayJournal.ItemReceipt(capture.captureId(), "HELD", "CANDIDATE_RESULT_MISMATCH", parsedDigest,
				parsedJson, List.of(), List.of()), List.of());
		if ("CANARY".equals(phase)) return append(plan, phase, capture,
			new ReplayJournal.ItemReceipt(capture.captureId(), "PARSED", null, parsedDigest, parsedJson, List.of(), List.of()), List.of());
		if (!plan.equals(authorized(plan.operationId(), plan.manifestDigest()))) throw new IllegalStateException("REPLAY_PLAN_CHANGED");
		// The same transaction still owns raw-observation and sync-state locks; no visit counters, signal refresh or receipts change.
		var proposals = reconcile.reconcileCapturedReplay(capture.storeId(), parsed, diagnostic.menuAllowed(), diagnostic.baeminAllowed());
		List<Long> created = proposals.stream().filter(ReconcileService.ReplayProposal::created).map(ReconcileService.ReplayProposal::proposalId).toList();
		List<Long> reused = proposals.stream().filter(p -> !p.created()).map(ReconcileService.ReplayProposal::proposalId).toList();
		String reason = proposals.stream().map(ReconcileService.ReplayProposal::reviewHoldReason).filter(Objects::nonNull).findFirst().orElse(null);
		return append(plan, phase, capture, new ReplayJournal.ItemReceipt(capture.captureId(),
			proposals.isEmpty() ? "NO_CHANGE" : "PROPOSED", reason, parsedDigest, parsedJson, created, reused), proposals);
	}

	private ReplayJournal.ItemReceipt append(ReplayPlan plan, String phase, ReplayJournal.Capture capture,
			ReplayJournal.ItemReceipt receipt, List<ReconcileService.ReplayProposal> proposals) {
		journal.append(plan.operationId(), phase, capture, receipt, proposals);
		return receipt;
	}
	private boolean successful(ReplayJournal.ItemReceipt receipt) {
		return List.of("PARSED", "PROPOSED", "NO_CHANGE").contains(receipt.status());
	}
	private String digest(Object value) { return ResponseDigests.sha256(ResponseDigests.canonical(mapper.valueToTree(value), mapper)); }
}
