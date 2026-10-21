package sweetgirl.dataops.replay;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;

import sweetgirl.dataops.runtimeattestation.RuntimeMeasurement;
import sweetgirl.dataops.snapshot.service.ResponseDigests;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Explicit disposable local PostgreSQL only; each test owns an isolated schema. */
@EnabledIfEnvironmentVariable(named="DATAOPS_BRIDGE_TEST_JDBC_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/agent_bridge_test")
class JdbcVerifiedReplayAuthorityPostgresTest {
    final ObjectMapper mapper=new ObjectMapper();
    final String operation="release-"+"1".repeat(32), generation="generation-2", service="bridge-test";
    final UUID replayId=UUID.randomUUID();
    final RuntimeMeasurement.Measurement measured=new RuntimeMeasurement.Measurement(h('a'),h('b'),h('c'),h('d'),
        "contract-v1",h('e'),h('f'),h('0'),h('1'));
    final AtomicReference<RuntimeMeasurement.Measurement> live=new AtomicReference<>(measured);
    JdbcTemplate admin,jdbc;String schema,url,manifestDigest,approvalDigest;Connection controller;
    ObjectNode document,validation,plan,canary;

    @BeforeEach void setup() throws Exception {
        url=System.getenv("DATAOPS_BRIDGE_TEST_JDBC_URL");
        admin=new JdbcTemplate(new DriverManagerDataSource(url,"postgres",""));
        schema="bridge_"+UUID.randomUUID().toString().replace("-","");
        admin.execute("CREATE SCHEMA "+schema);
        jdbc=new JdbcTemplate(new DriverManagerDataSource(url+"?currentSchema="+schema,"postgres",""));
        jdbc.execute("CREATE TABLE response_agent_run(run_id UUID PRIMARY KEY)");
        jdbc.execute("CREATE TABLE response_capture(capture_id VARCHAR(64) PRIMARY KEY)");
        jdbc.execute("CREATE TABLE change_proposal(id BIGINT PRIMARY KEY)");
        for(String file:List.of("V20261014170000__agent_review_outbox.sql","V20261014180000__agent_capture_replay.sql",
                "V20261014190000__agent_release_journal.sql","V20261014210000__agent_execution_binding.sql","V20261014230000__agent_active_runtime.sql"))
            jdbc.execute(Files.readString(Path.of("src/main/resources/db/dataops-migration",file)));
        documents();
    }
    @AfterEach void cleanup() throws Exception {
        if(controller!=null)controller.close();
        if(admin!=null&&schema!=null)admin.execute("DROP SCHEMA "+schema+" CASCADE");
    }

    @Test void exact_consumed_parser_approval_with_live_fence_preserves_manifest_order_and_full_oracles() throws Exception {
        seed();lock();
        var authority=authority("CANARY");var approved=authority.require(replayId,manifestDigest);
        assertThat(approved.targets()).extracting(ReplayPlan.Target::captureId).containsExactly("capture-z","capture-a");
        assertThat(approved.targets()).extracting(ReplayPlan.Target::expectedParsedDigest).containsExactly(h('2'),h('3'));
        assertThat(approved.canaryCaptureIds()).containsExactly("capture-z");
        assertThat(authority.currentRuntime().servingDatabaseIdentity()).isEqualTo(measured.servingDatabaseIdentity());
        assertThatThrownBy(()->authority.require(UUID.randomUUID(),manifestDigest)).hasMessage("EXECUTION_BINDING_MISMATCH");
    }
    @Test void absent_or_unconsumed_approval_cannot_be_replaced_by_process_arguments() {
        assertThatThrownBy(()->authority("CANARY").inspectBinding()).hasMessage("TRUSTED_EXECUTION_BINDING_REQUIRED");
        seed();
        jdbc.update("UPDATE response_agent_review_approval SET state='APPROVED',operation_id=NULL,consumed_at=NULL");
        assertThatThrownBy(()->authority("CANARY").inspectBinding()).hasMessage("TRUSTED_EXECUTION_BINDING_REQUIRED");
    }
    @Test void expired_or_revoked_manifest_keeps_readonly_recovery_but_blocks_further_replay() throws Exception {
        document.put("expires_at",Instant.now().minusSeconds(1).toString());seed();lock();
        var authority=authority("CANARY");
        assertThat(authority.inspectBinding().replayOperationId()).isEqualTo(replayId);
        assertThatThrownBy(()->authority.require(replayId,manifestDigest)).hasMessage("EXECUTION_APPROVAL_STALE");
        jdbc.update("UPDATE response_agent_review_manifest SET review_state='REJECTED'");
        assertThat(authority.inspectBinding().plan().targets()).hasSize(2);
        assertThatThrownBy(()->authority("STATUS").require(replayId,manifestDigest)).hasMessage("STATUS_CANNOT_EXECUTE_REPLAY");
        // DATA_APPLY has its own separately consumed approval and may measure a still-active old parser release.
        assertThat(authority.currentRuntime().releaseId()).isEqualTo("release-candidate");
    }
    @Test void controller_lock_epoch_phase_and_active_generation_are_each_required() throws Exception {
        seed();var authority=authority("CANARY");
        assertThatThrownBy(()->authority.require(replayId,manifestDigest)).hasMessage("RELEASE_FENCE_LOST");
        lock();jdbc.update("UPDATE response_agent_release_service SET fencing_epoch=8");
        assertThatThrownBy(()->authority.require(replayId,manifestDigest)).hasMessage("RELEASE_FENCE_LOST");
        jdbc.update("UPDATE response_agent_release_service SET fencing_epoch=7");
        jdbc.update("UPDATE response_agent_release_operation SET state='REPLAY_IN_FLIGHT',pending_action='replay'");
        assertThatThrownBy(()->authority.require(replayId,manifestDigest)).hasMessage("RELEASE_PHASE_MISMATCH");
        jdbc.update("UPDATE response_agent_release_operation SET state='CANARY_IN_FLIGHT',pending_action='canary'");
        jdbc.update("UPDATE response_agent_active_runtime SET generation='old-generation'");
        assertThatThrownBy(()->authority.require(replayId,manifestDigest)).hasMessage("ACTIVE_RUNTIME_CHANGED");
        jdbc.update("UPDATE response_agent_active_runtime SET generation=?,state='DRAINING'",generation);
        assertThatThrownBy(authority::currentRuntime).hasMessage("ACTIVE_RUNTIME_REQUIRED");
    }
    @Test void actual_artifact_profile_policy_and_both_databases_are_measured() throws Exception {
        seed();lock();var authority=authority("CANARY");
        for(int field=0;field<9;field++) {
            var values=new String[]{measured.artifactSha256(),measured.parserDigest(),measured.profileDigest(),measured.contractDigest(),
                measured.contractVersion(),measured.policyDigest(),measured.dataopsDatabaseIdentity(),measured.servingDatabaseIdentity(),measured.databaseIdentity()};
            values[field]="different";
            live.set(new RuntimeMeasurement.Measurement(values[0],values[1],values[2],values[3],values[4],values[5],values[6],values[7],values[8]));
            assertThatThrownBy(()->authority.require(replayId,manifestDigest)).hasMessage("MEASURED_RUNTIME_CHANGED");
        }
    }
    @Test void same_size_reordered_oracle_cannot_rebind_an_approved_capture() {
        ((ObjectNode)validation.path("capture_oracles").get(0)).put("external_id","foreign-place");
        plan.set("targets",validation.path("capture_oracles").deepCopy());seed();
        assertThatThrownBy(()->authority("STATUS").inspectBinding()).hasMessage("CAPTURE_ORACLE_BINDING_CHANGED");
    }
    @Test void generated_oracle_kind_and_wrong_canary_aggregate_fail_closed() {
        ((ObjectNode)validation.path("capture_oracles").get(0)).put("oracle_kind","MODEL_GENERATED");
        plan.set("targets",validation.path("capture_oracles").deepCopy());seed();
        assertThatThrownBy(()->authority("STATUS").inspectBinding()).hasMessage("INDEPENDENT_ORACLE_REQUIRED");
    }
    @Test void wrong_independent_canary_result_cannot_be_approved_by_a_matching_parser_digest() {
        canary.put("expected_result_digest",h('9'));seed();
        assertThatThrownBy(()->authority("STATUS").inspectBinding()).hasMessage("CANARY_ORACLE_CHANGED");
    }
    @Test void immutable_binding_enforces_one_python_operation_to_one_java_uuid_and_no_public_insert_grant() {
        seed();
        assertThatThrownBy(()->jdbc.update("UPDATE response_agent_execution_binding SET generation='forged'"))
            .hasMessageContaining("execution authority binding is immutable");
        assertThatThrownBy(()->jdbc.update("DELETE FROM response_agent_execution_binding"))
            .hasMessageContaining("execution authority binding is immutable");
        assertThatThrownBy(()->jdbc.execute("INSERT INTO response_agent_execution_binding SELECT * FROM response_agent_execution_binding"))
            .hasMessageContaining("duplicate key");
        assertThat(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_class c,LATERAL aclexplode(c.relacl) a WHERE c.oid='response_agent_execution_binding'::regclass AND a.grantee=0 AND a.privilege_type='INSERT')",Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE conrelid='response_agent_execution_binding'::regclass AND contype='u'",Integer.class)).isEqualTo(1);
    }
    @Test void apply_scope_keeps_a_real_shared_lock_until_close_and_rechecks_ownership() throws Exception {
        seed();jdbc.update("UPDATE response_agent_release_operation SET state='ACTIVE',pending_action=NULL");
        var authority=authority("STATUS");long key=JdbcVerifiedReplayAuthority.serviceLockKey(service);
        try(var scope=authority.openApplyScope()) {
            scope.assertHeld();assertThat(authority.currentRuntime().artifactDigest()).isEqualTo(measured.artifactSha256());
            assertThat(jdbc.queryForObject("SELECT pg_try_advisory_lock(?)",Boolean.class,key)).isFalse();
            assertThatThrownBy(authority::openApplyScope).hasMessage("APPLY_SCOPE_ALREADY_OPEN");
            Integer pid=jdbc.queryForObject("SELECT pid FROM pg_locks WHERE locktype='advisory' AND mode='ShareLock' AND classid::bigint=? AND objid::bigint=?",Integer.class,key>>>32,key&0xffffffffL);
            jdbc.queryForObject("SELECT pg_terminate_backend(?)",Boolean.class,pid);
            assertThatThrownBy(authority::currentRuntime).hasMessage("APPLY_SCOPE_LOST");
        } catch(IllegalStateException expectedCloseFailure) {
            assertThat(expectedCloseFailure).hasMessage("APPLY_SCOPE_RELEASE_FAILED");
        }
        try(var next=authority.openApplyScope()){next.assertHeld();}
        try(Connection connection=jdbc.getDataSource().getConnection();var statement=connection.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
            statement.setLong(1,key);try(var result=statement.executeQuery()){assertThat(result.next()).isTrue();assertThat(result.getBoolean(1)).isTrue();}
        }
    }
    @Test void deployment_exclusive_lock_prevents_new_apply_scope() throws Exception {
        seed();lock();
        assertThatThrownBy(()->authority("STATUS").openApplyScope()).hasMessage("RELEASE_TRANSITION_IN_PROGRESS");
    }
    @Test void durable_status_distinguishes_incomplete_unknown_from_completed_unattempted_without_executing() {
        seed();var binding=authority("STATUS").inspectBinding();
        var replay=mock(CaptureReplayService.class);
        var runner=new ReplayBridgeRunner(authority("STATUS"),replay,jdbc.getDataSource(),mapper,new MockEnvironment(),mock(ApplicationContext.class));
        var first=runner.status(binding,"REPLAY");
        assertThat(first.path("outcome").asString()).isEqualTo("UNKNOWN");
        assertThat(first.path("items").get(1).path("status").asString()).isEqualTo("UNKNOWN");
        jdbc.update("INSERT INTO response_capture VALUES ('capture-z'),('capture-a')");
        var journal=new JdbcReplayJournal(jdbc,mapper);journal.begin(binding.plan());
        var item=mapper.valueToTree(new ReplayJournal.ItemReceipt("capture-z","HELD","UNRECOGNIZED_FORMAT",h('2'),"private raw content",List.of(),List.of()));
        jdbc.update("INSERT INTO response_replay_receipt(operation_id,capture_id,phase,status,receipt_json,original_capture_json) VALUES (?,'capture-z','REPLAY','HELD',?::jsonb,'{}')",replayId,mapper.writeValueAsString(item));
        assertThat(runner.status(binding,"REPLAY").path("items").get(1).path("status").asString()).isEqualTo("UNKNOWN");
        journal.complete(replayId,"REPLAY",false);
        var stopped=runner.status(binding,"REPLAY");
        assertThat(stopped.path("outcome").asString()).isEqualTo("DONE");
        assertThat(stopped.path("passed").asBoolean()).isFalse();
        assertThat(stopped.path("items").get(1).path("status").asString()).isEqualTo("NOT_ATTEMPTED");
        assertThat(stopped.toString()).doesNotContain("private raw content","parsedJson");
        assertThat(stopped.path("operating_writes").asInt()).isZero();verifyNoInteractions(replay);
    }

    void documents() {
        var targets=mapper.createArrayNode();var oracles=mapper.createArrayNode();
        for(int i=0;i<2;i++) {
            var target=targets.addObject().put("capture_id",i==0?"capture-z":"capture-a").put("payload_digest",h('4'))
                .put("store_id",i+1).put("external_id",i==0?"place-z":"place-a").put("observed_at","2026-10-14T00:00:00Z").put("attempt_seq",i);
            var oracle=target.deepCopy();oracle.put("expected_parsed_digest",i==0?h('2'):h('3')).put("oracle_kind","HUMAN_CONFIRMED")
                .put("oracle_reference","reviewed-full-capture-v1").put("oracle_digest",h('5'));oracles.add(oracle);
        }
        var expected=mapper.createArrayNode();expected.addObject().put("capture_id","capture-z").put("parsed_digest",h('2'));
        canary=mapper.createObjectNode().put("mode","READ_ONLY").put("expected_result_digest",sha(expected));
        canary.putArray("targets").add(targets.get(0));
        validation=mapper.createObjectNode().put("schema_version",1).put("kind","FULL_SERVICE_CANDIDATE_VALIDATION")
            .put("base_build_receipt_digest",h('6')).put("base_artifact_sha256",h('7')).put("base_commit","1".repeat(40))
            .put("candidate_artifact_sha256",measured.artifactSha256()).put("candidate_parser_digest",measured.parserDigest())
            .put("candidate_profile_digest",measured.profileDigest()).put("runtime_contract_digest",measured.contractDigest())
            .put("semantic_source_contract_digest",h('8')).put("current_validation_digest",h('9'))
            .put("candidate_tree_digest",h('a')).put("patch_digest",h('b')).put("build_recipe_digest",h('c'));
        validation.set("capture_oracles",oracles);
        plan=mapper.createObjectNode().put("schema_version",1);plan.set("targets",oracles.deepCopy());plan.putArray("canary_capture_ids").add("capture-z");
        document=mapper.createObjectNode().put("kind","PARSER_REPAIR").put("scope","DEPLOY_REPLAY_PROPOSE")
            .put("expires_at",Instant.now().plusSeconds(600).toString()).put("base_commit","1".repeat(40))
            .put("artifact_sha256",measured.artifactSha256()).put("structure_profile_digest",measured.profileDigest())
            .put("semantic_contract_digest",measured.contractDigest()).put("candidate_tree_digest",h('a'))
            .put("patch_digest",h('b')).put("build_recipe_digest",h('c')).put("row_limit",2)
            .put("replay_target_manifest_digest",sha(targets));document.set("targets",targets);
    }
    void seed() {
        document.put("validation_digest",sha(validation)).put("canary_policy_digest",sha(canary));manifestDigest=sha(document);
        var evidence=mapper.createObjectNode().put("manifest_digest",manifestDigest).put("scope","DEPLOY_REPLAY_PROPOSE")
            .put("repository","org/dataops").put("pr_number",7).put("head_sha","2".repeat(40));approvalDigest=sha(evidence);
        UUID run=UUID.randomUUID();jdbc.update("INSERT INTO response_agent_run VALUES (?)",run);
        jdbc.update("INSERT INTO response_agent_review_manifest(manifest_digest,run_id,revision,kind,scope,document,expires_at,review_state) VALUES (?,?,1,'PARSER_REPAIR','DEPLOY_REPLAY_PROPOSE',?::jsonb,?,'VALIDATED')",
            manifestDigest,run,canonical(document),OffsetDateTime.parse(document.path("expires_at").asString()));
        jdbc.update("INSERT INTO response_agent_review_publication(manifest_digest,repository,pr_number,head_sha) VALUES (?,'org/dataops',7,?)",manifestDigest,"2".repeat(40));
        jdbc.update("INSERT INTO response_agent_review_approval(approval_digest,manifest_digest,event_key,actor_id,scope,evidence,state,operation_id,consumed_at) VALUES (?,?,'merge-1',1,'DEPLOY_REPLAY_PROPOSE',?::jsonb,'CONSUMED',?,CURRENT_TIMESTAMP)",approvalDigest,manifestDigest,canonical(evidence),operation);
        jdbc.update("INSERT INTO response_agent_release_service(service_id,fencing_epoch) VALUES (?,7)",service);
        jdbc.update("INSERT INTO response_agent_release_operation(operation_id,service_id,approval_digest,manifest_digest,request_digest,approval_scope,state,pending_action) VALUES (?,?,?,?,?,'DEPLOY_REPLAY_PROPOSE','CANARY_IN_FLIGHT','canary')",operation,service,approvalDigest,manifestDigest,h('f'));
        jdbc.update("""
            INSERT INTO response_agent_execution_binding(release_operation_id,replay_operation_id,manifest_digest,approval_digest,service_id,release_id,generation,
             artifact_digest,parser_digest,profile_digest,contract_digest,contract_version,policy_digest,dataops_database_identity,serving_database_identity,database_identity,
             validation_json,validation_digest,plan_json,plan_digest,canary_policy_json,canary_policy_digest)
            VALUES (?,?,?,?,?,'release-candidate',?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,operation,replayId,manifestDigest,approvalDigest,service,generation,measured.artifactSha256(),measured.parserDigest(),measured.profileDigest(),
            measured.contractDigest(),measured.contractVersion(),measured.policyDigest(),measured.dataopsDatabaseIdentity(),measured.servingDatabaseIdentity(),measured.databaseIdentity(),
            canonical(validation),sha(validation),canonical(plan),sha(plan),canonical(canary),sha(canary));
        jdbc.update("""
            INSERT INTO response_agent_active_runtime(service_id,generation,release_id,artifact_sha256,parser_digest,profile_digest,contract_digest,
             database_identity,dataops_database_identity,serving_database_identity,state,fence_epoch)
            VALUES (?,?,'release-candidate',?,?,?,?,?,?,?,'ACTIVE',6)
            """,service,generation,measured.artifactSha256(),measured.parserDigest(),measured.profileDigest(),measured.contractDigest(),
            measured.databaseIdentity(),measured.dataopsDatabaseIdentity(),measured.servingDatabaseIdentity());
    }
    JdbcVerifiedReplayAuthority authority(String action){return new JdbcVerifiedReplayAuthority(jdbc,mapper,live::get,operation,
        manifestDigest==null?h('a'):manifestDigest,generation,7,action);}
    void lock() throws Exception {
        controller=jdbc.getDataSource().getConnection();
        try(var statement=controller.prepareStatement("SELECT pg_advisory_lock(?)")){statement.setLong(1,JdbcVerifiedReplayAuthority.serviceLockKey(service));statement.execute();}
    }
    String canonical(JsonNode node){return ResponseDigests.canonical(node,mapper);}
    String sha(JsonNode node){return ResponseDigests.sha256(canonical(node));}
    static String h(char value){return String.valueOf(value).repeat(64);}
}
