package sweetgirl.dataops.agentapply;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import sweetgirl.dataops.proposal.entity.ChangeProposal;
import sweetgirl.dataops.replay.VerifiedReplayAuthority;
import sweetgirl.dataops.snapshot.service.ResponseDiagnostics;
import sweetgirl.dataops.snapshot.service.ResponseDigests;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.NullNode;

/** Closed by default. Checks the trusted approval ledger and immutable replay lineage before claim/write. */
@Component
@ConditionalOnProperty(name = "dataops.agent.apply.enabled", havingValue = "true")
public class AgentManifestApplyGate {
	private final JdbcTemplate jdbc;
	private final ObjectMapper mapper;
	private final ResponseDiagnostics diagnostics;
	private final ObjectProvider<VerifiedReplayAuthority> authorityProvider;

	public AgentManifestApplyGate(DataSource dataSource, ObjectMapper mapper, ResponseDiagnostics diagnostics,
			ObjectProvider<VerifiedReplayAuthority> authorityProvider) {
		this.jdbc = new JdbcTemplate(dataSource);
		this.mapper = mapper;
		this.diagnostics = diagnostics;
		this.authorityProvider = authorityProvider;
	}

	/** Freeze one consumer operation. Repeated delivery returns the same scope, never another operation. */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public List<Long> begin(AgentApplyRequest request) {
		Map<String, Object> row = approval(request, true);
		JsonNode document = document(row);
		verifyRuntime(document,request);
		String state = (String)row.get("state");
		if ("APPROVED".equals(state)) {
			int consumed = jdbc.update("""
				UPDATE response_agent_review_approval SET state='CONSUMED',operation_id=?,consumed_at=CURRENT_TIMESTAMP
				WHERE approval_digest=? AND state='APPROVED'
				""", request.operationId().toString(), request.approvalDigest());
			require(consumed == 1, "APPROVAL_ALREADY_CONSUMED");
		} else require("CONSUMED".equals(state) && request.operationId().toString().equals(row.get("operation_id")),
			"APPROVAL_ALREADY_CONSUMED");
		List<Long> ids = new ArrayList<>();
		for (JsonNode target : document.path("targets")) ids.add(target.path("proposal_id").asLong());
		return List.copyOf(ids);
	}

	/** Called inside the REQUIRES_NEW claim transaction after taking the proposal row lock. */
	public void verifyClaim(ChangeProposal proposal, AgentApplyRequest request) {
		require(TransactionSynchronizationManager.isActualTransactionActive(), "CLAIM_TRANSACTION_REQUIRED");
		// Serialize admissions across different stores sharing one approval, not only the proposal row.
		verify(proposal, request, true);
	}

	/** Re-read approval expiry/runtime/latest observation immediately before the conditional serving write. */
	public String verifyBeforeWrite(ChangeProposal proposal, AgentApplyRequest request) { return verify(proposal, request, false); }

	public void recordClaim(ChangeProposal proposal, AgentApplyRequest request, UUID proposalOperation) {
		require(TransactionSynchronizationManager.isActualTransactionActive(), "CLAIM_TRANSACTION_REQUIRED");
		jdbc.update("""
			INSERT INTO response_agent_data_apply_item(approval_digest,operation_id,proposal_id,proposal_operation_id)
			VALUES (?,?,?,?)
			""", request.approvalDigest(),request.operationId(),proposal.getId(),proposalOperation);
	}

	/** A successful duplicate can be returned; APPLYING/UNKNOWN are never retried blindly. */
	public boolean alreadyApplied(ChangeProposal proposal, AgentApplyRequest request) {
		if (!"APPROVED".equals(proposal.getStatus().name())) return false;
		Integer count = jdbc.queryForObject("""
			SELECT COUNT(*) FROM response_agent_data_apply_item WHERE approval_digest=? AND operation_id=?
			AND proposal_id=? AND proposal_operation_id=?
			""",Integer.class,request.approvalDigest(),request.operationId(),proposal.getId(),proposal.getOperationId());
		return count != null && count == 1;
	}

	private String verify(ChangeProposal proposal, AgentApplyRequest request, boolean lockApproval) {
		Map<String,Object> row = approval(request,lockApproval);
		require("CONSUMED".equals(row.get("state")) && request.operationId().toString().equals(row.get("operation_id")),
			"APPROVAL_OPERATION_MISMATCH");
		JsonNode document = document(row);
		verifyRuntime(document,request);
		JsonNode target = null;
		for (JsonNode item : document.path("targets")) if (item.path("proposal_id").asLong() == proposal.getId()) target = item;
		require(target != null, "PROPOSAL_OUTSIDE_MANIFEST");
		require("UPDATE".equals(target.path("operation").asString()), "REGISTRATION_REQUIRES_SEPARATE_ADAPTER");
		// The legacy manual upsert can update a menu inserted after preview. Until an insert-only CAS
		// adapter exists, an approved absence cannot authorize that update.
		require(!proposal.getTarget().startsWith("menu.add."), "MENU_INSERT_REQUIRES_CONDITIONAL_ADAPTER");
		if (Set.of("store.address","store.region","store.location").contains(proposal.getTarget()))
			throw new AgentApplyHold("FIXED_LOCATION_MANIFEST_REQUIRED");
		require(target.path("store_id").asLong() == proposal.getStoreId()
			&& target.path("field").asString().equals(proposal.getTarget())
			&& target.path("old_value").equals(value(proposal.getOldValue()))
			&& target.path("new_value").equals(value(proposal.getNewValue())), "PROPOSAL_CONTENT_CHANGED");
		verifySupportedValues(proposal);
		Integer unsettled = jdbc.queryForObject("""
			SELECT COUNT(*) FROM response_agent_data_apply_item i JOIN change_proposal p ON p.id=i.proposal_id
			WHERE i.approval_digest=? AND i.operation_id=? AND p.id<>? AND p.status IN ('APPLYING','FAILED','UNKNOWN')
			""",Integer.class,request.approvalDigest(),request.operationId(),proposal.getId());
		require(unsettled != null && unsettled == 0,"PRIOR_ITEM_REQUIRES_RECONCILIATION");
		UUID replayOperation;
		try { replayOperation = UUID.fromString(target.path("reprocess_item_id").asString()); }
		catch (RuntimeException invalid) { throw new AgentApplyHold("REPLAY_LINEAGE_INVALID"); }
		List<Map<String,Object>> lineage = jdbc.queryForList("""
			SELECT r.release_id,r.artifact_digest,r.parser_digest,r.profile_digest,p.original_proposal_json::text AS original,
			       c.payload_digest,c.payload,c.external_id
			FROM response_replay_proposal p JOIN response_replay_run r USING(operation_id)
			JOIN response_replay_receipt receipt ON receipt.operation_id=p.operation_id AND receipt.capture_id=p.capture_id AND receipt.phase='REPLAY'
			JOIN response_replay_completion canary ON canary.operation_id=p.operation_id AND canary.phase='CANARY' AND canary.passed
			JOIN response_replay_completion completed ON completed.operation_id=p.operation_id AND completed.phase='REPLAY'
			JOIN response_capture c ON c.capture_id=p.capture_id AND c.store_id=? AND c.source='NAVER'
			JOIN store_sync_state sync ON sync.store_id=c.store_id AND sync.source=c.source AND sync.external_id=c.external_id
			WHERE p.operation_id=? AND p.capture_id=? AND p.proposal_id=? AND receipt.status='PROPOSED'
			""",proposal.getStoreId(),replayOperation,target.path("capture_id").asString(),proposal.getId());
		require(lineage.size() == 1,"REPLAY_LINEAGE_MISSING");
		Map<String,Object> source = lineage.get(0);
		require(target.path("release_id").asString().equals(source.get("release_id"))
			&& document.path("base_release").asString().equals(source.get("release_id"))
			&& document.path("artifact_sha256").asString().equals(source.get("artifact_digest"))
			&& diagnostics.runtimeParserDigest().equals(source.get("parser_digest"))
			&& diagnostics.structureProfile().digest().equals(source.get("profile_digest")), "REPLAY_RUNTIME_MISMATCH");
		require(target.path("capture_digest").asString().equals(source.get("payload_digest"))
			&& target.path("capture_digest").asString().equals(ResponseDigests.sha256((String)source.get("payload")))
			&& target.path("external_id").asString().equals(source.get("external_id")), "CAPTURE_BINDING_CHANGED");
		JsonNode original = mapper.readTree((String)source.get("original"));
		require(original.path("target").asString().equals(proposal.getTarget())
			&& lineageValue(original.path("oldValue")).equals(value(proposal.getOldValue()))
			&& lineageValue(original.path("newValue")).equals(value(proposal.getNewValue())), "REPLAY_PROPOSAL_CHANGED");
		List<String> latest = jdbc.query("""
			SELECT capture_id FROM response_capture WHERE store_id=? AND source='NAVER' AND external_id=?
			AND (target_verified=TRUE OR status='RECEIVED')
			AND ((observed_at IS NULL AND status='RECEIVED') OR (observed_at>=? AND observed_at<=received_at+INTERVAL '5 minute'))
			ORDER BY COALESCE(observed_at,received_at) DESC,received_at DESC,capture_id DESC LIMIT 1
			""",(rs,index)->rs.getString(1),proposal.getStoreId(),target.path("external_id").asString(),
			java.time.OffsetDateTime.parse("1970-01-01T00:00:00Z"));
		require(latest.size() == 1 && latest.get(0).equals(target.path("capture_id").asString()), "NEWER_OBSERVATION_EXISTS");
		return target.path("external_id").asString();
	}

	private Map<String,Object> approval(AgentApplyRequest request, boolean lock) {
		List<Map<String,Object>> rows = jdbc.queryForList("""
			SELECT a.state,a.operation_id,m.manifest_digest,m.document::text AS document,m.expires_at
			FROM response_agent_review_approval a JOIN response_agent_review_manifest m USING(manifest_digest)
			JOIN response_agent_review_publication publication USING(manifest_digest)
			WHERE a.approval_digest=? AND a.scope='APPLY_EXACT_MANIFEST' AND m.scope='APPLY_EXACT_MANIFEST'
			AND m.kind='DATA_APPLY' AND m.review_state='VALIDATED' AND m.expires_at>CURRENT_TIMESTAMP
			""" + (lock ? " FOR UPDATE OF a,m" : ""),request.approvalDigest());
		require(rows.size() == 1,"AUTHENTICATED_DATA_APPROVAL_REQUIRED");
		return rows.get(0);
	}

	private JsonNode document(Map<String,Object> row) {
		JsonNode doc = mapper.readTree((String)row.get("document"));
		require(ResponseDigests.sha256(ResponseDigests.canonical(doc,mapper)).equals(row.get("manifest_digest")),"MANIFEST_DIGEST_CHANGED");
		require("DATA_APPLY".equals(doc.path("kind").asString()) && "APPLY_EXACT_MANIFEST".equals(doc.path("scope").asString())
			&& Instant.parse(doc.path("expires_at").asString()).isAfter(Instant.now()),"MANIFEST_SCOPE_EXPIRED");
		JsonNode targets = doc.path("targets");
		require(targets.isArray() && !targets.isEmpty() && targets.size() <= 1000
			&& targets.size() == doc.path("row_limit").asInt(),"MANIFEST_TARGET_LIMIT");
		var ids = new HashSet<Long>();
		for (JsonNode target : targets) require(target.path("proposal_id").isIntegralNumber()
			&& target.path("proposal_id").asLong() > 0 && ids.add(target.path("proposal_id").asLong()),"MANIFEST_TARGET_DUPLICATE");
		return doc;
	}

	private void verifyRuntime(JsonNode document, AgentApplyRequest request) {
		VerifiedReplayAuthority authority = authorityProvider.getIfAvailable();
		require(authority != null,"TRUSTED_RUNTIME_ATTESTOR_REQUIRED");
		VerifiedReplayAuthority.RuntimeIdentity actual = authority.currentRuntime();
		require(actual != null,"TRUSTED_RUNTIME_ATTESTOR_REQUIRED");
		ServingDatabaseIdentity.requireMatches(actual.servingDatabaseIdentity(),request.servingDatabaseIdentity());
		require(actual != null && document.path("base_release").asString().equals(actual.releaseId())
			&& document.path("artifact_sha256").asString().equals(actual.artifactDigest())
			&& document.path("structure_profile_digest").asString().equals(diagnostics.structureProfile().digest()),"RUNNING_ARTIFACT_CHANGED");
	}

	private JsonNode value(String json) { return json == null ? NullNode.getInstance() : mapper.readTree(json); }

	/** Only fields whose existing writer applies the exact preview values are enabled in this slice. */
	void verifySupportedValues(ChangeProposal proposal) {
		String field=proposal.getTarget();
		JsonNode old=value(proposal.getOldValue()), next=value(proposal.getNewValue());
		if (Set.of("store.name","store.phone","store.instagram_handle").contains(field)) {
			require((old.isNull() || old.isString()) && next.isString() && !next.asString().isBlank(),"EXACT_VALUE_TYPE_REQUIRED");
			return;
		}
		if ("store.business_hours".equals(field)) {
			verifyHours(old,true);verifyHours(next,false);return;
		}
		if (field.matches("menu\\.[1-9][0-9]*\\.price")) {
			require((old.isNull() || (old.isIntegralNumber() && old.canConvertToInt()))
				&& next.isIntegralNumber() && next.canConvertToInt() && next.asInt()>=0,"EXACT_VALUE_TYPE_REQUIRED");return;
		}
		if (field.matches("menu\\.[1-9][0-9]*\\.description")) {
			require((old.isNull() || old.isString()) && next.isString() && next.asString().codePointCount(0,next.asString().length())<=255,
				"EXACT_VALUE_TYPE_REQUIRED");return;
		}
		throw new AgentApplyHold("EXACT_FIELD_ADAPTER_REQUIRED");
	}
	private void verifyHours(JsonNode rows,boolean allowEmpty) {
		require(rows.isArray() && rows.size()<=7 && (allowEmpty || !rows.isEmpty()),"EXACT_HOURS_VALUE_REQUIRED");
		var days=new HashSet<String>();
		for(JsonNode row:rows) {
			require(row.isObject() && row.size()==4 && row.has("day") && row.has("open") && row.has("close") && row.has("closed")
				&& row.path("day").isString() && Set.of("MONDAY","TUESDAY","WEDNESDAY","THURSDAY","FRIDAY","SATURDAY","SUNDAY").contains(row.path("day").asString())
				&& days.add(row.path("day").asString()) && row.path("closed").isBoolean(),"EXACT_HOURS_VALUE_REQUIRED");
			for(String field:List.of("open","close"))require(row.path(field).isNull() || (row.path(field).isString()
				&& row.path(field).asString().matches("(?:[01][0-9]|2[0-3]):[0-5][0-9]")),"EXACT_HOURS_VALUE_REQUIRED");
		}
	}

	private JsonNode lineageValue(JsonNode node) { return node.isString() ? value(node.asString()) : node; }
	private static void require(boolean condition,String code) { if (!condition) throw new AgentApplyHold(code); }
}
