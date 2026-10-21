package sweetgirl.dataops.replay;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import sweetgirl.dataops.runtimeattestation.RuntimeMeasurement;
import sweetgirl.dataops.snapshot.service.ResponseDigests;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Controller-issued database authority. Process arguments identify a binding; they never create one. */
@Component
@ConditionalOnProperty(name="dataops.agent.execution.enabled",havingValue="true")
public class JdbcVerifiedReplayAuthority implements VerifiedReplayAuthority {
	private static final Set<String> CAPTURE_FIELDS=Set.of("capture_id","payload_digest","store_id","external_id","observed_at","attempt_seq");
	private static final Set<String> ORACLE_FIELDS=Set.of("capture_id","payload_digest","store_id","external_id","observed_at","attempt_seq",
		"expected_parsed_digest","oracle_kind","oracle_reference","oracle_digest");
	private static final Set<String> VALIDATION_FIELDS=Set.of("schema_version","kind","base_build_receipt_digest","base_artifact_sha256",
		"base_commit","candidate_artifact_sha256","candidate_parser_digest","candidate_profile_digest","runtime_contract_digest",
		"semantic_source_contract_digest","current_validation_digest","candidate_tree_digest","patch_digest","build_recipe_digest","capture_oracles");
	private final JdbcTemplate jdbc;
	private final ObjectMapper mapper;
	private final Supplier<RuntimeMeasurement.Measurement> measure;
	private final String releaseOperation, manifestDigest, generation;
	private final long fenceEpoch;
	private final String action;
	private final ThreadLocal<SharedApplyScope> applyScope=new ThreadLocal<>();

	@Autowired
	public JdbcVerifiedReplayAuthority(DataSource primary,ObjectMapper mapper,RuntimeMeasurement measurement,Environment environment) {
		this(new JdbcTemplate(primary),mapper,measurement::measure,required(environment,"release-operation-id"),
			required(environment,"manifest-digest"),required(environment,"generation"),
			Long.parseLong(required(environment,"fence-epoch")),environment.getProperty("dataops.agent.replay.action","STATUS"));
	}

	JdbcVerifiedReplayAuthority(JdbcTemplate jdbc,ObjectMapper mapper,Supplier<RuntimeMeasurement.Measurement> measure,
			String releaseOperation,String manifestDigest,String generation,long fenceEpoch,String action) {
		check(releaseOperation!=null && releaseOperation.matches("release-[0-9a-f]{32}")
			&& hash(manifestDigest) && generation!=null && !generation.isBlank() && fenceEpoch>0,"EXECUTION_CONFIGURATION_INVALID");
		check(Set.of("CANARY","REPLAY","STATUS").contains(action),"EXECUTION_ACTION_INVALID");
		this.jdbc=jdbc; this.mapper=mapper; this.measure=measure; this.releaseOperation=releaseOperation;
		this.manifestDigest=manifestDigest; this.generation=generation; this.fenceEpoch=fenceEpoch; this.action=action;
	}

	public record Binding(String releaseOperationId,UUID replayOperationId,String manifestDigest,String serviceId,String generation,
		ReplayPlan plan,JsonNode manifestTargets,JsonNode canaryPolicy,String databaseIdentity,String contractDigest) {}

	@Override public ReplayPlan require(UUID operationId,String digest) {
		check(!"STATUS".equals(action),"STATUS_CANNOT_EXECUTE_REPLAY");
		Binding binding=load(true);
		check(binding.replayOperationId().equals(operationId) && binding.manifestDigest().equals(digest),"EXECUTION_BINDING_MISMATCH");
		return binding.plan();
	}

	/** Expired/revoked authority can still inspect its original immutable receipts, never perform work. */
	public Binding inspectBinding() { return load(false); }

	@Override public RuntimeIdentity currentRuntime() {
		load(false);
		if(applyScope.get()!=null) applyScope.get().assertHeld();
		Map<String,Object> row=joined(false);
		check(Set.of("VERIFYING","CANARY","CANARY_IN_FLIGHT","REPLAY","REPLAY_IN_FLIGHT","ACTIVE","REPLAY_HELD")
			.contains(row.get("release_state")),"CANDIDATE_RUNTIME_NOT_ACTIVE");
		RuntimeMeasurement.Measurement actual=measurement(row);
		return new RuntimeIdentity((String)row.get("release_id"),actual.artifactSha256(),actual.servingDatabaseIdentity());
	}

	@Override public ApplyScope openApplyScope() {
		check(applyScope.get()==null,"APPLY_SCOPE_ALREADY_OPEN");
		Map<String,Object> row=joined(false);
		long key=serviceLockKey((String)row.get("service_id"));
		Connection connection=null;
		try {
			connection=jdbc.getDataSource().getConnection();
			connection.setAutoCommit(true);
			try(var statement=connection.prepareStatement("SELECT pg_try_advisory_lock_shared(?)")) {
				statement.setLong(1,key);statement.setQueryTimeout(5);
				try(var result=statement.executeQuery()){check(result.next()&&result.getBoolean(1),"RELEASE_TRANSITION_IN_PROGRESS");}
			}
			var scope=new SharedApplyScope(connection,key);
			applyScope.set(scope);
			try{currentRuntime();return scope;}catch(RuntimeException failure){scope.close();throw failure;}
		} catch(SQLException | RuntimeException failure) {
			if(connection!=null)try{connection.close();}catch(SQLException ignored){}
			if(failure instanceof RuntimeException runtime)throw runtime;
			throw new IllegalStateException("APPLY_SCOPE_UNAVAILABLE",failure);
		}
	}

	private final class SharedApplyScope implements ApplyScope {
		private final Connection connection;private final long key;private boolean closed;
		SharedApplyScope(Connection connection,long key){this.connection=connection;this.key=key;}
		@Override public void assertHeld() {
			check(!closed && applyScope.get()==this,"APPLY_SCOPE_LOST");
			try(var query=connection.prepareStatement("SELECT EXISTS(SELECT 1 FROM pg_locks WHERE locktype='advisory' "
				+"AND pid=pg_backend_pid() AND mode='ShareLock' AND granted AND objsubid=1 AND classid::bigint=? AND objid::bigint=?)")) {
				query.setLong(1,key>>>32);query.setLong(2,key&0xffffffffL);query.setQueryTimeout(5);
				try(var result=query.executeQuery()){check(result.next()&&result.getBoolean(1),"APPLY_SCOPE_LOST");}
			} catch(SQLException failure){throw new IllegalStateException("APPLY_SCOPE_LOST",failure);}
		}
		@Override public void close() {
			if(closed)return;closed=true;applyScope.remove();
			try {
				try(var statement=connection.prepareStatement("SELECT pg_advisory_unlock_shared(?)")){statement.setLong(1,key);statement.execute();}
			} catch(SQLException failure){throw new IllegalStateException("APPLY_SCOPE_RELEASE_FAILED",failure);}
			finally{try{connection.close();}catch(SQLException failure){throw new IllegalStateException("APPLY_SCOPE_RELEASE_FAILED",failure);}}
		}
	}

	private Binding load(boolean execute) {
		Map<String,Object> row=joined(execute);
		JsonNode document=mapper.readTree((String)row.get("document"));
		check(sha(document).equals(manifestDigest),"PARSER_MANIFEST_DIGEST_CHANGED");
		check("PARSER_REPAIR".equals(document.path("kind").asString())
			&& "DEPLOY_REPLAY_PROPOSE".equals(document.path("scope").asString()),"PARSER_APPROVAL_REQUIRED");
		JsonNode evidence=mapper.readTree((String)row.get("evidence"));
		check(sha(evidence).equals(row.get("approval_digest"))
			&& manifestDigest.equals(evidence.path("manifest_digest").asString())
			&& "DEPLOY_REPLAY_PROPOSE".equals(evidence.path("scope").asString())
			&& evidence.path("repository").asString().equals(row.get("repository"))
			&& evidence.path("pr_number").asLong()==((Number)row.get("pr_number")).longValue()
			&& evidence.path("head_sha").asString().equals(row.get("head_sha")),"APPROVAL_EVIDENCE_CHANGED");
		Instant expires=Instant.parse(document.path("expires_at").asString());
		if(execute) {
			check("VALIDATED".equals(row.get("review_state")) && expires.isAfter(Instant.now())
				&& Boolean.TRUE.equals(row.get("unexpired")),"EXECUTION_APPROVAL_STALE");
			check((action+"_IN_FLIGHT").equals(row.get("release_state"))
				&& action.toLowerCase(java.util.Locale.ROOT).equals(row.get("pending_action")),"RELEASE_PHASE_MISMATCH");
			check(((Number)row.get("fencing_epoch")).longValue()==fenceEpoch && controllerLockPresent((String)row.get("service_id")),"RELEASE_FENCE_LOST");
			measurement(row);
		}
		JsonNode validation=boundJson(row,"validation"), plan=boundJson(row,"plan"), canary=boundJson(row,"canary_policy");
		check(fields(validation).equals(VALIDATION_FIELDS) && validation.path("schema_version").asInt()==1
			&& "FULL_SERVICE_CANDIDATE_VALIDATION".equals(validation.path("kind").asString()),"FULL_CAPTURE_VALIDATION_REQUIRED");
		for(String field:VALIDATION_FIELDS) if(!Set.of("schema_version","kind","base_commit","capture_oracles").contains(field))
			check(hash(validation.path(field).asString()),"VALIDATION_DIGEST_REQUIRED");
		check(validation.path("base_commit").asString().equals(document.path("base_commit").asString()),"VALIDATION_BASE_CHANGED");
		for(String[] pair:new String[][]{{"candidate_artifact_sha256","artifact_digest"},{"candidate_parser_digest","parser_digest"},
			{"candidate_profile_digest","profile_digest"},{"runtime_contract_digest","contract_digest"}})
			check(validation.path(pair[0]).asString().equals(row.get(pair[1])),"VALIDATION_RUNTIME_CHANGED");
		for(String[] pair:new String[][]{{"candidate_tree_digest","candidate_tree_digest"},{"patch_digest","patch_digest"},
			{"build_recipe_digest","build_recipe_digest"}})
			check(validation.path(pair[0]).equals(document.path(pair[1])),"VALIDATION_APPROVAL_CHANGED");
		for(String[] pair:new String[][]{{"validation_digest","validation_digest"},{"artifact_digest","artifact_sha256"},
			{"profile_digest","structure_profile_digest"},{"contract_digest","semantic_contract_digest"},
			{"canary_policy_digest","canary_policy_digest"}})
			check(row.get(pair[0]).equals(document.path(pair[1]).asString()),"EXECUTION_MANIFEST_CHANGED");
		check(fields(plan).equals(Set.of("schema_version","targets","canary_capture_ids")) && plan.path("schema_version").asInt()==1,
			"REPLAY_PLAN_SCHEMA");
		JsonNode approved=document.path("targets"), oracles=validation.path("capture_oracles");
		check(approved.isArray() && !approved.isEmpty() && approved.size()<=1000
			&& document.path("row_limit").asInt()==approved.size() && sha(approved).equals(document.path("replay_target_manifest_digest").asString())
			&& oracles.isArray() && oracles.size()==approved.size() && oracles.equals(plan.path("targets")),"FULL_CAPTURE_TARGETS_CHANGED");
		List<ReplayPlan.Target> targets=new ArrayList<>();
		for(int i=0;i<approved.size();i++) {
			JsonNode raw=approved.get(i),oracle=oracles.get(i);
			check(fields(raw).equals(CAPTURE_FIELDS) && fields(oracle).equals(ORACLE_FIELDS),"CAPTURE_TARGET_SCHEMA");
			for(String field:CAPTURE_FIELDS) check(raw.path(field).equals(oracle.path(field)),"CAPTURE_ORACLE_BINDING_CHANGED");
			check(Set.of("REVIEWED_CONTRACT","APPROVED_BASELINE","HUMAN_CONFIRMED").contains(oracle.path("oracle_kind").asString())
				&& !oracle.path("oracle_reference").asString().isBlank() && hash(oracle.path("oracle_digest").asString()),"INDEPENDENT_ORACLE_REQUIRED");
			check(raw.path("store_id").isIntegralNumber() && raw.path("store_id").canConvertToLong()
				&& raw.path("attempt_seq").isIntegralNumber() && raw.path("attempt_seq").canConvertToLong(),"CAPTURE_NUMBER_INVALID");
			targets.add(new ReplayPlan.Target(raw.path("capture_id").asString(),raw.path("payload_digest").asString(),
				raw.path("store_id").asLong(),raw.path("external_id").asString(),Instant.parse(raw.path("observed_at").asString()),
				raw.path("attempt_seq").asLong(),oracle.path("expected_parsed_digest").asString()));
		}
		check(fields(canary).equals(Set.of("mode","targets","expected_result_digest")) && "READ_ONLY".equals(canary.path("mode").asString())
			&& plan.path("canary_capture_ids").isArray(),"CANARY_POLICY_INVALID");
		List<String> canaryIds=new ArrayList<>();plan.path("canary_capture_ids").forEach(id->canaryIds.add(id.asString()));
		var canaryTargets=mapper.createArrayNode();var expected=mapper.createArrayNode();
		for(int i=0;i<targets.size();i++) if(canaryIds.contains(targets.get(i).captureId())) {
			canaryTargets.add(approved.get(i));expected.addObject().put("capture_id",targets.get(i).captureId())
				.put("parsed_digest",targets.get(i).expectedParsedDigest());
		}
		check(canaryTargets.equals(canary.path("targets")) && sha(expected).equals(canary.path("expected_result_digest").asString()),"CANARY_ORACLE_CHANGED");
		ReplayPlan replay=new ReplayPlan((UUID)row.get("replay_operation_id"),manifestDigest,(String)row.get("release_id"),
			(String)row.get("artifact_digest"),(String)row.get("parser_digest"),(String)row.get("profile_digest"),
			(String)row.get("contract_version"),(String)row.get("policy_digest"),expires,targets,canaryIds);
		return new Binding(releaseOperation,replay.operationId(),manifestDigest,(String)row.get("service_id"),generation,replay,
			approved,canary,(String)row.get("database_identity"),(String)row.get("contract_digest"));
	}

	private Map<String,Object> joined(boolean lock) {
		String locking=lock && TransactionSynchronizationManager.isActualTransactionActive()?" FOR SHARE OF s,r,m,a":"";
		var rows=jdbc.queryForList("""
			SELECT b.*,r.state AS release_state,r.pending_action,s.fencing_epoch,m.document::text,m.review_state,
			 m.expires_at>CURRENT_TIMESTAMP AS unexpired,a.evidence::text,p.repository,p.pr_number,p.head_sha
			FROM response_agent_execution_binding b
			JOIN response_agent_release_operation r ON r.operation_id=b.release_operation_id AND r.manifest_digest=b.manifest_digest
			 AND r.approval_digest=b.approval_digest AND r.service_id=b.service_id AND r.approval_scope='DEPLOY_REPLAY_PROPOSE'
			JOIN response_agent_release_service s ON s.service_id=b.service_id
			JOIN response_agent_review_manifest m ON m.manifest_digest=b.manifest_digest AND m.kind='PARSER_REPAIR' AND m.scope='DEPLOY_REPLAY_PROPOSE'
			JOIN response_agent_review_approval a ON a.approval_digest=b.approval_digest AND a.manifest_digest=b.manifest_digest
			 AND a.state='CONSUMED' AND a.operation_id=b.release_operation_id AND a.scope='DEPLOY_REPLAY_PROPOSE'
			JOIN response_agent_review_publication p ON p.manifest_digest=b.manifest_digest
			WHERE b.release_operation_id=? AND b.manifest_digest=? AND b.generation=?
			"""+locking,releaseOperation,manifestDigest,generation);
		check(rows.size()==1,"TRUSTED_EXECUTION_BINDING_REQUIRED");return rows.get(0);
	}

	private RuntimeMeasurement.Measurement measurement(Map<String,Object> row) {
		String locking=TransactionSynchronizationManager.isActualTransactionActive()?" FOR SHARE":"";
		var active=jdbc.queryForList("SELECT * FROM response_agent_active_runtime WHERE service_id=? AND state='ACTIVE'"+locking,row.get("service_id"));
		check(active.size()==1,"ACTIVE_RUNTIME_REQUIRED");
		Map<String,Object> live=active.get(0);
		check(row.get("artifact_digest").equals(live.get("artifact_sha256")),"ACTIVE_RUNTIME_CHANGED");
		for(String field:List.of("release_id","generation","parser_digest","profile_digest","contract_digest",
			"database_identity","dataops_database_identity","serving_database_identity"))
			check(row.get(field).equals(live.get(field)),"ACTIVE_RUNTIME_CHANGED");
		var value=measure.get();
		check(value!=null && value.artifactSha256().equals(row.get("artifact_digest"))
			&& value.parserDigest().equals(row.get("parser_digest")) && value.profileDigest().equals(row.get("profile_digest"))
			&& value.contractDigest().equals(row.get("contract_digest")) && value.contractVersion().equals(row.get("contract_version"))
			&& value.policyDigest().equals(row.get("policy_digest")) && value.dataopsDatabaseIdentity().equals(row.get("dataops_database_identity"))
			&& value.servingDatabaseIdentity().equals(row.get("serving_database_identity")) && value.databaseIdentity().equals(row.get("database_identity")),
			"MEASURED_RUNTIME_CHANGED");return value;
	}

	private JsonNode boundJson(Map<String,Object> row,String field) {
		String text=(String)row.get(field+"_json");
		check(text!=null && ResponseDigests.sha256(text).equals(row.get(field+"_digest")),"EXECUTION_DOCUMENT_DIGEST_CHANGED");
		JsonNode value=mapper.readTree(text);
		check(text.equals(ResponseDigests.canonical(value,mapper)),"EXECUTION_DOCUMENT_NOT_CANONICAL");return value;
	}

	private boolean controllerLockPresent(String serviceId) {
		long key=serviceLockKey(serviceId);
		return Boolean.TRUE.equals(jdbc.queryForObject("""
			SELECT EXISTS(SELECT 1 FROM pg_locks WHERE locktype='advisory' AND mode='ExclusiveLock' AND granted
			 AND objsubid=1 AND classid::bigint=? AND objid::bigint=? AND database=(SELECT oid FROM pg_database WHERE datname=current_database()))
			""",Boolean.class,key>>>32,key&0xffffffffL));
	}
	public static long serviceLockKey(String serviceId) {
		try{return ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(("agent-release:"+serviceId).getBytes(StandardCharsets.UTF_8))).getLong()&Long.MAX_VALUE;}
		catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
	}
	private String sha(JsonNode value){return ResponseDigests.sha256(ResponseDigests.canonical(value,mapper));}
	private static Set<String> fields(JsonNode value){var result=new HashSet<String>();value.properties().forEach(e->result.add(e.getKey()));return result;}
	private static boolean hash(String value){return value!=null&&value.matches("[0-9a-f]{64}");}
	private static void check(boolean condition,String reason){if(!condition)throw new IllegalStateException(reason);}
	private static String required(Environment environment,String field){return environment.getRequiredProperty("dataops.agent.execution."+field);}
}
