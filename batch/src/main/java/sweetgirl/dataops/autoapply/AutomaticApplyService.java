package sweetgirl.dataops.autoapply;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import sweetgirl.dataops.agentapply.AgentApplyHold;
import sweetgirl.dataops.proposal.entity.ChangeProposal;
import sweetgirl.dataops.proposal.enums.ProposalStatus;
import sweetgirl.dataops.proposal.exception.ProposalException;
import sweetgirl.dataops.proposal.repository.ChangeProposalRepository;
import sweetgirl.dataops.proposal.service.ProposalCommandService;
import sweetgirl.dataops.reconcile.enums.ProvenanceSource;
import sweetgirl.dataops.reconcile.repository.StoreFieldProvenanceRepository;
import sweetgirl.dataops.reconcile.service.ReconcileService;
import sweetgirl.dataops.snapshot.concurrency.StoreObservationBarrier;
import sweetgirl.dataops.snapshot.dto.request.SnapshotSubmitRequest;
import sweetgirl.dataops.snapshot.parser.NaverPlaceParser;
import sweetgirl.dataops.snapshot.service.ResponseDiagnostics;
import sweetgirl.dataops.sync.enums.SyncSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Prepare/commit first, then claim and apply. No serving write runs in the snapshot transaction. */
@Service
@ConditionalOnProperty(name = "dataops.auto-apply.enabled", havingValue = "true")
public class AutomaticApplyService {
	private final JdbcTemplate jdbc;
	private final TransactionTemplate transaction;
	private final ObjectMapper mapper;
	private final StoreObservationBarrier barrier;
	private final ResponseDiagnostics diagnostics;
	private final NaverPlaceParser parser;
	private final ReconcileService reconcile;
	private final ChangeProposalRepository proposals;
	private final StoreFieldProvenanceRepository provenance;
	private final AutomaticApplyPolicy policy;
	private final ProposalCommandService commands;
	private final AutomaticApplyWriter writer;
	private final String policyVersion;

	public AutomaticApplyService(DataSource dataSource, PlatformTransactionManager transactionManager,
			ObjectMapper mapper, StoreObservationBarrier barrier, ResponseDiagnostics diagnostics, NaverPlaceParser parser,
			ReconcileService reconcile, ChangeProposalRepository proposals, StoreFieldProvenanceRepository provenance,
			AutomaticApplyPolicy policy, ProposalCommandService commands, AutomaticApplyWriter writer) {
		this.jdbc = new JdbcTemplate(dataSource);
		this.transaction = new TransactionTemplate(transactionManager);
		this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.mapper = mapper;
		this.barrier = barrier;
		this.diagnostics = diagnostics;
		this.parser = parser;
		this.reconcile = reconcile;
		this.proposals = proposals;
		this.provenance = provenance;
		this.policy = policy;
		this.commands = commands;
		this.writer = writer;
		this.policyVersion = AutomaticApplyPolicy.VERSION + ":" + sweetgirl.dataops.snapshot.service.ResponseDigests.sha256(
			diagnostics.runtimeParserDigest() + sweetgirl.dataops.snapshot.service.ResponseDigests.classes(
				AutomaticApplyPolicy.class, ReconcileService.class, ProposalCommandService.class,
				sweetgirl.dataops.serving.client.ServingWriter.class));
	}

	public record Item(long proposalId, String status, String reason) {}
	public record Result(String captureId, int applied, int alreadyApplied, int held, int failed, int unknown,
			String observationHold, List<Item> items) {
		static Result of(String captureId, String observationHold, List<Item> items) {
			return new Result(captureId, count(items, "APPLIED"), count(items, "ALREADY_APPLIED"),
				count(items, "HELD"), count(items, "FAILED"), count(items, "UNKNOWN"), observationHold, List.copyOf(items));
		}
		private static int count(List<Item> items, String status) {
			return (int)items.stream().filter(item -> status.equals(item.status())).count();
		}
	}
	private record Capture(SnapshotSubmitRequest request, boolean verified, String status, JsonNode receipt) {}
	private record Plan(String version, List<AutomaticCandidate> candidates, JsonNode results) {}

	public Result apply(String captureId) {
		long storeId = capture(captureId).request().storeId();
		try (var scope = barrier.lockApproval(storeId)) {
			Capture capture = capture(captureId);
			Plan plan = loadPlan(captureId);
			if (plan == null) {
				try {
					verifyCapture(capture);
					plan = transaction.execute(status -> prepare(capture));
				} catch (AutomaticApplyHold held) {
					return Result.of(captureId, held.getMessage(), List.of());
				}
			}
			List<Item> items = new ArrayList<>();
			for (AutomaticCandidate candidate : Objects.requireNonNull(plan).candidates()) {
				String key = Long.toString(candidate.proposalId());
				if (plan.results().has(key)) {
					Item saved = mapper.treeToValue(plan.results().get(key), Item.class);
					items.add("APPLIED".equals(saved.status()) ? new Item(saved.proposalId(), "ALREADY_APPLIED", "REPLAY") : saved);
					continue;
				}
				Item item;
				try {
					if (!policyVersion.equals(plan.version())) throw new AutomaticApplyHold("POLICY_CHANGED");
					verifyCapture(capture);
					ChangeProposal proposal = proposal(candidate.proposalId());
					if (proposal.getStatus() != ProposalStatus.PENDING) {
						item = settled(proposal);
					} else {
						commands.approveAutomatically(candidate.proposalId(), writer.writer(), scope,
							new ProposalCommandService.AutomaticApproval(capture.request().externalId(), locked -> {
								verifyCapture(capture);
								policy.verify(locked, candidate, capture.request().storeId(), sources(capture.request().storeId()));
							}));
						item = new Item(candidate.proposalId(), "APPLIED", "VALIDATED");
					}
				} catch (AutomaticApplyHold | AgentApplyHold held) {
					item = new Item(candidate.proposalId(), "HELD", held.getMessage());
				} catch (ProposalException failure) {
					ChangeProposal current = proposal(candidate.proposalId());
					item = current.getStatus() == ProposalStatus.PENDING
						? new Item(candidate.proposalId(), "HELD", failure.getErrorCode().toString()) : settled(current);
				}
				jdbc.update("""
					UPDATE response_auto_apply SET results = results || jsonb_build_object(CAST(? AS text), CAST(? AS jsonb)),
					updated_at = now() WHERE capture_id = ?
					""", key, mapper.writeValueAsString(item), captureId);
				items.add(item);
			}
			return Result.of(captureId, null, items);
		}
	}

	private Item settled(ChangeProposal proposal) {
		String reason = Objects.toString(proposal.getApplyOutcomeReason(), proposal.getStatus().name());
		String status = switch (proposal.getStatus()) {
			case APPROVED -> "ALREADY_APPLIED";
			case APPLYING, UNKNOWN -> "UNKNOWN";
			case FAILED -> SetReasons.CONFLICTS.contains(reason) ? "HELD" : "FAILED";
			default -> "HELD";
		};
		return new Item(proposal.getId(), status, reason);
	}
	private static class SetReasons {
		static final java.util.Set<String> CONFLICTS = java.util.Set.of("VALUE_DRIFTED", "MENU_CATEGORY_CHANGED", "MENU_CONFLICT");
	}
	private ChangeProposal proposal(long id) {
		return proposals.findById(id).orElseThrow(() -> new AutomaticApplyHold("PROPOSAL_MISSING"));
	}
	private Map<String, ProvenanceSource> sources(long storeId) {
		Map<String, ProvenanceSource> result = new LinkedHashMap<>();
		provenance.findByStoreId(storeId).forEach(value -> result.put(value.getField(), value.getSource()));
		return result;
	}

	private Plan prepare(Capture capture) {
		var report = diagnostics.inspect(capture.request());
		if (report.held() || !report.targetVerified()) throw new AutomaticApplyHold("STRUCTURE_CHANGED");
		var parsed = ResponseDiagnostics.parsePreservingInput(parser, capture.request().payload());
		Long snapshotId = capture.receipt().path("snapshotId").isNumber() ? capture.receipt().path("snapshotId").asLong() : null;
		List<AutomaticCandidate> candidates = reconcile.reconcileAutomatic(capture.request().storeId(), snapshotId,
			parsed, report.menuAllowed(), report.baeminAllowed());
		jdbc.update("INSERT INTO response_auto_apply (capture_id, policy_version, candidates) VALUES (?, ?, CAST(? AS jsonb))",
			capture.request().observationId(), policyVersion, mapper.writeValueAsString(candidates));
		return new Plan(policyVersion, candidates, mapper.createObjectNode());
	}
	private Plan loadPlan(String captureId) {
		return jdbc.query("SELECT policy_version, candidates::text, results::text FROM response_auto_apply WHERE capture_id = ?",
			(row, index) -> new Plan(row.getString(1), Arrays.asList(mapper.readValue(row.getString(2), AutomaticCandidate[].class)),
				mapper.readTree(row.getString(3))), captureId).stream().findFirst().orElse(null);
	}
	private Capture capture(String captureId) {
		return jdbc.query("SELECT * FROM response_capture WHERE capture_id = ?", (row, index) -> new Capture(
			new SnapshotSubmitRequest(row.getLong("store_id"), SyncSource.valueOf(row.getString("source")),
				mapper.readTree(row.getString("payload")), null, captureId, row.getString("external_id"),
				row.getObject("observed_at", OffsetDateTime.class), row.getObject("attempt_seq", Long.class)),
			row.getBoolean("target_verified"), row.getString("status"),
			mapper.readTree(Objects.toString(row.getString("processed_response"), "null"))), captureId)
			.stream().findFirst().orElseThrow(() -> new AutomaticApplyHold("CAPTURE_MISSING"));
	}
	private void verifyCapture(Capture capture) {
		if (!capture.verified() || capture.request().source() != SyncSource.NAVER || capture.request().observedAt() == null
			|| capture.receipt().isNull() || "HELD".equals(capture.status())
			|| !List.of("READY", "PARTIAL").contains(capture.receipt().path("assessment").asString("")))
			throw new AutomaticApplyHold("OBSERVATION_HELD");
		if (capture.request().observedAt().isBefore(OffsetDateTime.now().minusHours(6)))
			throw new AutomaticApplyHold("OBSERVATION_EXPIRED");
		// Even a newer RECEIVED/unverified raw is a barrier; it may reveal a changed structure.
		Boolean latest = jdbc.queryForObject("""
			SELECT NOT EXISTS (SELECT 1 FROM response_capture newer JOIN response_capture current ON current.capture_id = ?
			WHERE newer.store_id = current.store_id AND newer.source = current.source AND newer.capture_id <> current.capture_id
			AND (newer.received_at >= current.received_at OR newer.observed_at > current.observed_at))
			""", Boolean.class, capture.request().observationId());
		if (!Boolean.TRUE.equals(latest)) throw new AutomaticApplyHold("STALE_OBSERVATION");
	}
}
