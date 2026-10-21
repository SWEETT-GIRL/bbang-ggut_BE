package sweetgirl.dataops.replay;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import sweetgirl.dataops.reconcile.service.ReconcileService;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JdbcReplayJournal implements ReplayJournal {
	private final JdbcTemplate jdbc;
	private final ObjectMapper mapper;
	@Autowired
	public JdbcReplayJournal(DataSource primary,ObjectMapper mapper) {
		this(new JdbcTemplate(primary),mapper);
	}
	public JdbcReplayJournal(JdbcTemplate jdbc,ObjectMapper mapper) {
		this.jdbc=jdbc;this.mapper=mapper;
	}

	@Override
	public void begin(ReplayPlan plan) {
		String encoded = mapper.writeValueAsString(plan);
		jdbc.update("""
			INSERT INTO response_replay_run(operation_id,manifest_digest,release_id,artifact_digest,
			 parser_digest,profile_digest,contract_version,policy_digest,approved_plan)
			VALUES (?,?,?,?,?,?,?,?,?::jsonb) ON CONFLICT DO NOTHING
			""", plan.operationId(), plan.manifestDigest(), plan.releaseId(), plan.artifactDigest(),
			plan.parserDigest(), plan.profileDigest(), plan.contractVersion(), plan.policyDigest(), encoded);
		lock(plan.operationId(), plan.manifestDigest());
		String saved = jdbc.queryForObject("SELECT approved_plan::text FROM response_replay_run WHERE operation_id=?",
			String.class, plan.operationId());
		if (!mapper.readTree(encoded).equals(mapper.readTree(saved))) throw new IllegalStateException("REPLAY_PLAN_CHANGED");
		for (ReplayPlan.Target target : plan.targets()) {
			jdbc.update("""
				INSERT INTO response_replay_target(operation_id,capture_id,release_id,policy_digest,target_json,is_canary)
				VALUES (?,?,?,?,?::jsonb,?) ON CONFLICT(operation_id,capture_id) DO NOTHING
				""", plan.operationId(), target.captureId(), plan.releaseId(), plan.policyDigest(),
				mapper.writeValueAsString(target), plan.canaryCaptureIds().contains(target.captureId()));
		}
	}

	@Override
	public void lock(UUID operationId, String manifestDigest) {
		String saved = jdbc.queryForObject("SELECT manifest_digest FROM response_replay_run WHERE operation_id=? FOR UPDATE",
			String.class, operationId);
		if (!manifestDigest.equals(saved)) throw new IllegalStateException("REPLAY_MANIFEST_CHANGED");
	}

	@Override
	public Optional<Capture> capture(String captureId) {
		return jdbc.query("""
			SELECT capture_id,store_id,source,external_id,observed_at,received_at,attempt_seq,payload,payload_digest,
			 parser_digest,contract_version FROM response_capture WHERE capture_id=?
			""", (rs, row) -> new Capture(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4),
				rs.getObject(5, OffsetDateTime.class), rs.getObject(6, OffsetDateTime.class), rs.getObject(7, Long.class),
				rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11)), captureId).stream().findFirst();
	}

	@Override
	public boolean latest(Capture capture) {
		// A later HELD/partial/unassessed observation is still newer evidence. Unknown clocks fail closed.
		Boolean current = jdbc.queryForObject("""
			SELECT NOT EXISTS(SELECT 1 FROM response_capture newer
			 WHERE newer.store_id=? AND newer.source=? AND newer.external_id=? AND newer.capture_id<>?
			 AND (newer.observed_at > ? OR
			      (newer.observed_at = ? AND (newer.received_at > ? OR
			        (newer.received_at = ? AND newer.capture_id > ?))) OR
			      (newer.observed_at IS NULL AND newer.received_at >= ?)))
			""", Boolean.class, capture.storeId(), capture.source(), capture.externalId(), capture.captureId(),
			capture.observedAt(), capture.observedAt(), capture.receivedAt(), capture.receivedAt(), capture.captureId(),
			capture.receivedAt());
		return Boolean.TRUE.equals(current);
	}

	@Override
	public Optional<ItemReceipt> receipt(UUID operationId, String captureId, String phase) {
		return jdbc.query("SELECT receipt_json::text FROM response_replay_receipt WHERE operation_id=? AND capture_id=? AND phase=?",
			(rs, row) -> mapper.readValue(rs.getString(1), ItemReceipt.class), operationId, captureId, phase).stream().findFirst();
	}

	@Override
	public void append(UUID operationId, String phase, Capture capture, ItemReceipt receipt,
			List<ReconcileService.ReplayProposal> proposals) {
		var provenance = new java.util.LinkedHashMap<String, Object>();
		provenance.put("captureId", capture.captureId()); provenance.put("storeId", capture.storeId());
		provenance.put("externalId", capture.externalId()); provenance.put("source", capture.source());
		provenance.put("observedAt", capture.observedAt()); provenance.put("attemptSeq", capture.attemptSeq());
		provenance.put("payloadDigest", capture.payloadDigest()); provenance.put("originalParserDigest", capture.originalParserDigest());
		provenance.put("originalContractVersion", capture.originalContractVersion());
		jdbc.update("""
			INSERT INTO response_replay_receipt(operation_id,capture_id,phase,status,receipt_json,original_capture_json)
			VALUES (?,?,?,?,?::jsonb,?::jsonb)
			""", operationId, capture.captureId(), phase, receipt.status(), mapper.writeValueAsString(receipt),
			mapper.writeValueAsString(provenance));
		for (var proposal : proposals) {
			jdbc.update("""
				INSERT INTO response_replay_proposal(operation_id,capture_id,proposal_id,relation,original_proposal_json)
				VALUES (?,?,?,?,?::jsonb)
				""", operationId, capture.captureId(), proposal.proposalId(), proposal.created() ? "CREATED" : "REUSED",
				mapper.writeValueAsString(proposal));
		}
	}

	@Override
	public boolean canaryPassed(UUID operationId) {
		return jdbc.query("SELECT passed FROM response_replay_completion WHERE operation_id=? AND phase='CANARY'",
			(rs, row) -> rs.getBoolean(1), operationId).stream().findFirst().orElse(false);
	}

	@Override
	public void complete(UUID operationId, String phase, boolean passed) {
		jdbc.update("INSERT INTO response_replay_completion(operation_id,phase,passed) VALUES (?,?,?) ON CONFLICT DO NOTHING",
			operationId, phase, passed);
		Boolean original = jdbc.queryForObject("SELECT passed FROM response_replay_completion WHERE operation_id=? AND phase=?",
			Boolean.class, operationId, phase);
		if (!Boolean.valueOf(passed).equals(original)) throw new IllegalStateException("REPLAY_COMPLETION_CHANGED");
	}
}
