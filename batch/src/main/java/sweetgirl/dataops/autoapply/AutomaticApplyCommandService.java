package sweetgirl.dataops.autoapply;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import sweetgirl.dataops.snapshot.service.ResponseDigests;
import sweetgirl.dataops.serving.client.ServingWriter;
import sweetgirl.dataops.reconcile.policy.MergePolicy;
import sweetgirl.dataops.reconcile.policy.BeverageFilter;
import sweetgirl.dataops.reconcile.policy.MenuCategoryClassifier;

import sweetgirl.dataops.agentapply.AgentApplyHold;
import sweetgirl.dataops.autoapply.AutomaticApplyQueryService.CaptureResponseDto;
import sweetgirl.dataops.autoapply.AutomaticApplyQueryService.PlanResponseDto;
import sweetgirl.dataops.proposal.enums.ProposalStatus;
import sweetgirl.dataops.proposal.exception.ProposalException;
import sweetgirl.dataops.proposal.service.ProposalCommandService;
import sweetgirl.dataops.reconcile.service.ReconcileService;
import sweetgirl.dataops.snapshot.concurrency.StoreObservationBarrier;
import sweetgirl.dataops.snapshot.parser.NaverPlaceParser;
import sweetgirl.dataops.snapshot.service.ResponseDiagnostics;
import sweetgirl.dataops.sync.enums.SyncSource;
import tools.jackson.databind.ObjectMapper;

/** Prepare/commit first, then claim and apply. No serving write runs in the snapshot transaction. */
@Service
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@ConditionalOnProperty(name = "dataops.auto-apply.enabled", havingValue = "true")
public class AutomaticApplyCommandService {
	private final JdbcTemplate jdbc;
	private final TransactionTemplate transaction;
	private final ObjectMapper mapper;
	private final StoreObservationBarrier barrier;
	private final ResponseDiagnostics diagnostics;
	private final NaverPlaceParser parser;
	private final ReconcileService reconcile;
	private final AutomaticApplyQueryService queries;
	private final AutomaticApplyPolicy policy;
	private final ProposalCommandService commands;
	private final AutomaticApplyWriter writer;
	private final String policyVersion;

	public AutomaticApplyCommandService(DataSource dataSource, PlatformTransactionManager transactionManager,
			ObjectMapper mapper, StoreObservationBarrier barrier, ResponseDiagnostics diagnostics, NaverPlaceParser parser,
			ReconcileService reconcile, AutomaticApplyQueryService queries,
			AutomaticApplyPolicy policy, ProposalCommandService commands, AutomaticApplyWriter writer) {
		this.jdbc = new JdbcTemplate(dataSource);
		this.transaction = new TransactionTemplate(transactionManager);
		this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.mapper = mapper;
		this.barrier = barrier;
		this.diagnostics = diagnostics;
		this.parser = parser;
		this.reconcile = reconcile;
		this.queries = queries;
		this.policy = policy;
		this.commands = commands;
		this.writer = writer;
		this.policyVersion = AutomaticApplyPolicy.VERSION + ":" + ResponseDigests.sha256(
			diagnostics.runtimeParserDigest() + ResponseDigests.classes(
				AutomaticApplyPolicy.class, ReconcileService.class, ProposalCommandService.class,
				ServingWriter.class,
				MergePolicy.class,
				BeverageFilter.class,
				MenuCategoryClassifier.class,
				AutomaticApplyCommandService.class, AutomaticApplyQueryService.class));
	}

	public AutomaticApplyResponseDto apply(String captureId) {
		long storeId = queries.findCapture(captureId).request().storeId();
		try (StoreObservationBarrier.ApprovalScope scope = barrier.lockApproval(storeId)) {
			CaptureResponseDto capture = queries.findCapture(captureId);
			PlanResponseDto plan;
			try {
				plan = queries.findPlan(captureId).orElseGet(() -> {
					verifyCapture(capture);
					return transaction.execute(status -> prepare(capture));
				});
			} catch (AutomaticApplyException.Hold held) {
				return AutomaticApplyResponseDto.from(captureId, held.getMessage(), List.of());
			}
			List<AutomaticApplyItemResponseDto> items = new ArrayList<>();
			for (AutomaticCandidate candidate : Objects.requireNonNull(plan).candidates()) {
				String key = Long.toString(candidate.proposalId());
				if (plan.results().has(key)) {
					var saved = mapper.treeToValue(plan.results().get(key), AutomaticApplyItemResponseDto.class);
					items.add(saved.replayed());
					continue;
				}
				var outcome = applyCandidate(capture, plan, candidate, scope);
				jdbc.update("""
					UPDATE response_auto_apply SET results = results || jsonb_build_object(CAST(? AS text), CAST(? AS jsonb)),
					updated_at = now() WHERE capture_id = ?
					""", key, mapper.writeValueAsString(outcome), captureId);
				items.add(outcome);
			}
			return AutomaticApplyResponseDto.from(captureId, null, items);
		}
	}

	private AutomaticApplyItemResponseDto applyCandidate(CaptureResponseDto capture, PlanResponseDto plan,
			AutomaticCandidate candidate, StoreObservationBarrier.ApprovalScope scope) {
		try {
			if (!policyVersion.equals(plan.version())) throw new AutomaticApplyException.Hold(AutomaticApplyException.Reason.POLICY_CHANGED);
			verifyCapture(capture);
			var proposal = queries.findProposal(candidate.proposalId());
			if (proposal.status() != ProposalStatus.PENDING) return AutomaticApplyItemResponseDto.from(proposal);
			commands.approveAutomatically(candidate.proposalId(), writer.writer(), scope,
				new ProposalCommandService.AutomaticApproval(capture.request().externalId(), locked -> {
					verifyCapture(capture);
					policy.verify(locked, candidate, capture.request().storeId(), queries.findSources(capture.request().storeId()));
				}));
			return new AutomaticApplyItemResponseDto(candidate.proposalId(), "APPLIED", "VALIDATED");
		} catch (AutomaticApplyException.Hold | AgentApplyHold held) {
			return new AutomaticApplyItemResponseDto(candidate.proposalId(), "HELD", held.getMessage());
		} catch (ProposalException failure) {
			var current = queries.findProposal(candidate.proposalId());
			return current.status() == ProposalStatus.PENDING
				? new AutomaticApplyItemResponseDto(candidate.proposalId(), "HELD", failure.getErrorCode().toString())
				: AutomaticApplyItemResponseDto.from(current);
		}
	}

	private PlanResponseDto prepare(CaptureResponseDto capture) {
		var report = diagnostics.inspect(capture.request());
		if (report.held() || !report.targetVerified()) throw new AutomaticApplyException.Hold(AutomaticApplyException.Reason.STRUCTURE_CHANGED);
		var parsed = ResponseDiagnostics.parsePreservingInput(parser, capture.request().payload());
		Long snapshotId = capture.receipt().path("snapshotId").isNumber() ? capture.receipt().path("snapshotId").asLong() : null;
		List<AutomaticCandidate> candidates = reconcile.reconcileAutomatic(capture.request().storeId(), snapshotId,
			parsed, report.menuAllowed(), report.baeminAllowed());
		jdbc.update("INSERT INTO response_auto_apply (capture_id, policy_version, candidates) VALUES (?, ?, CAST(? AS jsonb))",
			capture.request().observationId(), policyVersion, mapper.writeValueAsString(candidates));
		return new PlanResponseDto(policyVersion, candidates, mapper.createObjectNode());
	}
	private void verifyCapture(CaptureResponseDto capture) {
		if (!capture.verified() || capture.request().source() != SyncSource.NAVER || capture.request().observedAt() == null
			|| capture.receipt().isNull() || "HELD".equals(capture.status())
			|| !List.of("READY", "PARTIAL").contains(capture.receipt().path("assessment").asString("")))
			throw new AutomaticApplyException.Hold(AutomaticApplyException.Reason.OBSERVATION_HELD);
		if (capture.request().observedAt().isBefore(OffsetDateTime.now().minusHours(6)))
			throw new AutomaticApplyException.Hold(AutomaticApplyException.Reason.OBSERVATION_EXPIRED);
		// Even a newer RECEIVED/unverified raw is a barrier; it may reveal a changed structure.
		if (!queries.isLatestCapture(capture.request().observationId())) throw new AutomaticApplyException.Hold(AutomaticApplyException.Reason.STALE_OBSERVATION);
	}
}
