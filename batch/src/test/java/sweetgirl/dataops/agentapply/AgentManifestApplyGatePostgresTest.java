package sweetgirl.dataops.agentapply;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import sweetgirl.dataops.proposal.entity.ChangeProposal;
import sweetgirl.dataops.reconcile.enums.ProvenanceSource;
import sweetgirl.dataops.replay.VerifiedReplayAuthority;
import sweetgirl.dataops.snapshot.parser.BusinessHoursParser;
import sweetgirl.dataops.snapshot.parser.MenuEvidenceParser;
import sweetgirl.dataops.snapshot.parser.NaverPlaceParser;
import sweetgirl.dataops.snapshot.service.ResponseDiagnostics;
import sweetgirl.dataops.snapshot.service.ResponseDigests;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Disposable local PostgreSQL only. No serving database or production credentials are used. */
@EnabledIfEnvironmentVariable(named="DATAOPS_AGENT_APPLY_TEST_JDBC_URL",matches="jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/.*")
class AgentManifestApplyGatePostgresTest {
	private final ObjectMapper mapper=new ObjectMapper();
	private final String approval="a".repeat(64);
	private final String artifact="b".repeat(64);
	private final UUID replay=UUID.randomUUID();
	private final AgentApplyRequest request=new AgentApplyRequest(approval,UUID.randomUUID(),"e".repeat(64));
	private JdbcTemplate admin,jdbc;
	private String schema;
	private TransactionTemplate transaction;
	private AgentManifestApplyGate gate;
	private VerifiedReplayAuthority authority;
	private ChangeProposal proposal;

	@BeforeEach @SuppressWarnings("unchecked") void setup(TestInfo testInfo) throws Exception {
		String base=System.getenv("DATAOPS_AGENT_APPLY_TEST_JDBC_URL");
		admin=new JdbcTemplate(new DriverManagerDataSource(base,"postgres",""));
		schema="agent_apply_"+UUID.randomUUID().toString().replace("-","");
		admin.execute("CREATE SCHEMA "+schema);
		var dataSource=new DriverManagerDataSource(base+(base.contains("?")?"&":"?")+"currentSchema="+schema,"postgres","");
		jdbc=new JdbcTemplate(dataSource);
		transaction=new TransactionTemplate(new DataSourceTransactionManager(dataSource));
		jdbc.execute("CREATE TABLE response_agent_run(run_id uuid primary key)");
		jdbc.execute("CREATE TABLE change_proposal(id bigint primary key,status text)");
		jdbc.execute("""
			CREATE TABLE response_capture(capture_id varchar(64) primary key,store_id bigint,source text,payload text,
			 payload_digest text,external_id text,target_verified boolean,status text,observed_at timestamptz,received_at timestamptz)
			""");
		jdbc.execute("CREATE TABLE store_sync_state(store_id bigint,source text,external_id text)");
		for(String name:List.of("V20261014170000__agent_review_outbox.sql","V20261014180000__agent_capture_replay.sql",
			"V20261014200000__agent_data_apply_item.sql"))
			jdbc.execute(Files.readString(Path.of("src/main/resources/db/dataops-migration",name)));
		UUID run=UUID.randomUUID();
		jdbc.update("INSERT INTO response_agent_run VALUES (?)",run);
		jdbc.update("INSERT INTO change_proposal VALUES (5,'PENDING')");
		jdbc.update("INSERT INTO store_sync_state VALUES (1,'NAVER','123')");
		String raw="{}", rawDigest=ResponseDigests.sha256(raw);
		String targetField="store.phone", oldJson="\"old\"", newJson="\"new\"";
		if(testInfo.getTags().contains("hours")) {
			targetField="store.business_hours"; oldJson="[]";
			newJson="[{\"day\":\"MONDAY\",\"open\":\"09:00\",\"close\":\"18:00\",\"closed\":false}]";
		}
		jdbc.update("INSERT INTO response_capture VALUES ('capture',1,'NAVER',?,?,'123',true,'HELD',CURRENT_TIMESTAMP-INTERVAL '2 minute',CURRENT_TIMESTAMP)",raw,rawDigest);
		var diagnostics=new ResponseDiagnostics(mapper,new NaverPlaceParser(),new BusinessHoursParser(),new MenuEvidenceParser(mapper));
		String profile=diagnostics.structureProfile().digest();
		ObjectNode doc=mapper.createObjectNode();
		doc.put("schema_version",1).put("review_id","review").put("kind","DATA_APPLY").put("scope","APPLY_EXACT_MANIFEST")
			.put("incident_id","incident").put("run_id",run.toString()).put("revision",1).put("base_release","release")
			.put("base_commit","c".repeat(40)).put("expires_at",OffsetDateTime.now().plusDays(1).toInstant().toString())
			.put("row_limit",1).put("preview_digest",artifact);
		for(String field:List.of("candidate_tree_digest","patch_digest","semantic_contract_digest","validation_digest",
			"build_recipe_digest","replay_target_manifest_digest","canary_policy_digest","artifact_sha256"))doc.put(field,artifact);
		doc.put("structure_profile_digest",profile);
		doc.putObject("files").put("docs/response-agent-reviews/review/review.md",artifact)
			.put("docs/response-agent-reviews/review/data-manifest.json",artifact);
		var target=doc.putArray("targets").addObject();
		target.put("proposal_id",5).put("store_id",1).put("external_id","123").put("operation","UPDATE")
			.put("field",targetField).put("capture_id","capture")
			.put("capture_digest",rawDigest).put("release_id","release").put("reprocess_item_id",replay.toString());
		target.set("old_value",mapper.readTree(oldJson));target.set("new_value",mapper.readTree(newJson));
		String manifest=ResponseDigests.sha256(ResponseDigests.canonical(doc,mapper));
		jdbc.update("""
			INSERT INTO response_agent_review_manifest(manifest_digest,run_id,revision,kind,scope,document,expires_at,review_state)
			VALUES (?,?,1,'DATA_APPLY','APPLY_EXACT_MANIFEST',?::jsonb,CURRENT_TIMESTAMP+INTERVAL '1 day','VALIDATED')
			""",manifest,run,doc.toString());
		jdbc.update("INSERT INTO response_agent_review_publication(manifest_digest,repository,pr_number,head_sha) VALUES (?,'SWEETT-GIRL/repo',1,?)",manifest,"c".repeat(40));
		jdbc.update("""
			INSERT INTO response_agent_review_approval(approval_digest,manifest_digest,event_key,actor_id,scope,evidence,state)
			VALUES (?,?,'merge-event',7,'APPLY_EXACT_MANIFEST','{}','APPROVED')
			""",approval,manifest);
		jdbc.update("INSERT INTO response_replay_run VALUES (?,?,?,?,?,?,?,?,?::jsonb,CURRENT_TIMESTAMP)",
			replay,"d".repeat(64),"release",artifact,diagnostics.runtimeParserDigest(),profile,ResponseDiagnostics.CONTRACT,artifact,"{}");
		jdbc.update("INSERT INTO response_replay_target VALUES (?,'capture','release',?,'{}',true)",replay,artifact);
		jdbc.update("INSERT INTO response_replay_receipt VALUES (?,'capture','REPLAY','PROPOSED','{}','{}',CURRENT_TIMESTAMP)",replay);
		String original=mapper.createObjectNode().put("target",targetField).put("oldValue",oldJson).put("newValue",newJson).toString();
		jdbc.update("INSERT INTO response_replay_proposal VALUES (?,'capture',5,'REUSED',?::jsonb,'REPLAY')",replay,original);
		jdbc.update("INSERT INTO response_replay_completion VALUES (?,'CANARY',true,CURRENT_TIMESTAMP),(?,'REPLAY',true,CURRENT_TIMESTAMP)",replay,replay);
		authority=mock(VerifiedReplayAuthority.class);
		when(authority.currentRuntime()).thenReturn(new VerifiedReplayAuthority.RuntimeIdentity("release",artifact,"e".repeat(64)));
		ObjectProvider<VerifiedReplayAuthority> provider=mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(authority);
		gate=new AgentManifestApplyGate(dataSource,mapper,diagnostics,provider);
		proposal=ChangeProposal.builder().storeId(1L).target(targetField).oldValue(oldJson).newValue(newJson)
			.source(ProvenanceSource.NAVER).build();
		ReflectionTestUtils.setField(proposal,"id",5L);
		transaction.executeWithoutResult(status->assertThat(gate.begin(request)).containsExactly(5L));
	}

	@AfterEach void cleanup(){if(admin!=null && schema!=null)admin.execute("DROP SCHEMA "+schema+" CASCADE");}

	@Test void exact_consumed_approval_and_replay_lineage_allow_claim_only_in_transaction() {
		assertThatThrownBy(()->gate.verifyClaim(proposal,request)).hasMessage("CLAIM_TRANSACTION_REQUIRED");
		transaction.executeWithoutResult(status->{gate.verifyClaim(proposal,request);gate.recordClaim(proposal,request,UUID.randomUUID());});
		assertThat(jdbc.queryForObject("SELECT count(*) FROM response_agent_data_apply_item",Integer.class)).isEqualTo(1);
		assertThatThrownBy(()->transaction.executeWithoutResult(status->gate.begin(new AgentApplyRequest(approval,UUID.randomUUID(),"e".repeat(64)))))
			.hasMessage("APPROVAL_ALREADY_CONSUMED");
	}

	@Test void latest_observation_for_any_field_and_current_target_mapping_are_required() {
		jdbc.update("INSERT INTO response_capture VALUES ('new',1,'NAVER','{}',?,'123',true,'RECEIVED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",ResponseDigests.sha256("{}"));
		assertThatThrownBy(()->gate.verifyBeforeWrite(proposal,request)).hasMessage("NEWER_OBSERVATION_EXISTS");
		jdbc.update("DELETE FROM response_capture WHERE capture_id='new'");
		jdbc.update("UPDATE store_sync_state SET external_id='999'");
		assertThatThrownBy(()->gate.verifyBeforeWrite(proposal,request)).hasMessage("REPLAY_LINEAGE_MISSING");
	}

	@Test void changed_proposal_artifact_raw_and_revoked_approval_are_held_before_write() {
		ReflectionTestUtils.setField(proposal,"newValue","\"changed\"");
		assertThatThrownBy(()->gate.verifyBeforeWrite(proposal,request)).hasMessage("PROPOSAL_CONTENT_CHANGED");
		ReflectionTestUtils.setField(proposal,"newValue","\"new\"");
		when(authority.currentRuntime()).thenReturn(new VerifiedReplayAuthority.RuntimeIdentity("release","0".repeat(64),"e".repeat(64)));
		assertThatThrownBy(()->gate.verifyBeforeWrite(proposal,request)).hasMessage("RUNNING_ARTIFACT_CHANGED");
		when(authority.currentRuntime()).thenReturn(new VerifiedReplayAuthority.RuntimeIdentity("release",artifact,"e".repeat(64)));
		jdbc.update("UPDATE response_capture SET payload='changed'");
		assertThatThrownBy(()->gate.verifyBeforeWrite(proposal,request)).hasMessage("CAPTURE_BINDING_CHANGED");
		jdbc.update("UPDATE response_agent_review_manifest SET review_state='SUPERSEDED'");
		assertThatThrownBy(()->gate.verifyBeforeWrite(proposal,request)).hasMessage("AUTHENTICATED_DATA_APPROVAL_REQUIRED");
	}

	@Test void failed_or_unknown_prior_item_prevents_scope_expansion() {
		jdbc.update("INSERT INTO change_proposal VALUES (6,'UNKNOWN')");
		jdbc.update("INSERT INTO response_agent_data_apply_item VALUES (?,?,6,?,CURRENT_TIMESTAMP)",approval,request.operationId(),UUID.randomUUID());
		assertThatThrownBy(()->gate.verifyBeforeWrite(proposal,request)).hasMessage("PRIOR_ITEM_REQUIRES_RECONCILIATION");
	}

	@Test void menu_insert_does_not_reuse_manual_upsert_that_can_overwrite_newly_created_rows() {
		ReflectionTestUtils.setField(proposal,"target","menu.add.bread");
		assertThatThrownBy(()->gate.verifyBeforeWrite(proposal,request)).hasMessage("MENU_INSERT_REQUIRES_CONDITIONAL_ADAPTER");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM response_agent_data_apply_item",Integer.class)).isZero();
	}

	@Test void location_fields_are_held_because_current_writer_changes_unfrozen_evidence() {
		for(String field:List.of("store.address","store.region","store.location")) {
			ReflectionTestUtils.setField(proposal,"target",field);
			assertThatThrownBy(()->gate.verifyBeforeWrite(proposal,request)).hasMessage("FIXED_LOCATION_MANIFEST_REQUIRED");
		}
	}

	@Test @Tag("hours") void valid_approved_hours_manifest_can_reach_the_separately_guarded_transactional_writer() {
		transaction.executeWithoutResult(status->gate.verifyClaim(proposal,request));
		assertThat(jdbc.queryForObject("SELECT status FROM change_proposal WHERE id=5",String.class)).isEqualTo("PENDING");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM response_agent_data_apply_item",Integer.class)).isZero();
	}

	@Test void serving_identity_is_measured_from_cluster_and_database_and_cannot_be_caller_asserted() {
		String measured=ServingDatabaseIdentity.measure(jdbc);
		assertThat(measured).matches("[0-9a-f]{64}");
		ServingDatabaseIdentity.requireMatches(measured,ServingDatabaseIdentity.measure(jdbc));
		assertThatThrownBy(()->ServingDatabaseIdentity.requireMatches("0".repeat(64),measured)).hasMessage("SERVING_DATABASE_IDENTITY_CHANGED");
		when(authority.currentRuntime()).thenReturn(new VerifiedReplayAuthority.RuntimeIdentity("release",artifact,"f".repeat(64)));
		assertThatThrownBy(()->gate.verifyBeforeWrite(proposal,request)).hasMessage("SERVING_DATABASE_IDENTITY_CHANGED");
	}

	@Test void exact_menu_writer_checks_store_user_contribution_liveness_and_value_at_the_dml() {
		jdbc.execute("CREATE TABLE store(id bigint primary key,status text,naver_place_id text)");
		jdbc.execute("CREATE TABLE menu(id bigint primary key,store_id bigint,price integer,description varchar(255),created_by_member_id bigint,deleted_at timestamptz,updated_at timestamptz)");
		jdbc.update("INSERT INTO store VALUES (1,'ACTIVE','123'),(2,'ACTIVE','456')");
		jdbc.update("INSERT INTO menu(id,store_id,price,description) VALUES(1,1,100,'old')");
		var writer=new sweetgirl.dataops.serving.client.ServingWriter(jdbc);
		assertThat(writer.updateMenuPrice(1,2,"123",100,200)).isZero();
		assertThat(writer.updateMenuPrice(1,1,"123",101,200)).isZero();
		assertThat(writer.updateMenuPrice(1,1,"123",100,200)).isEqualTo(1);
		assertThat(writer.updateMenuDescription(1,2,"123","old","new")).isZero();
		assertThat(writer.updateMenuDescription(1,1,"123","old","new")).isEqualTo(1);
		jdbc.update("UPDATE store SET naver_place_id='changed' WHERE id=1");
		assertThat(writer.updateMenuPrice(1,1,"123",200,300)).isZero();
		assertThat(writer.updateMenuDescription(1,1,"123","new","changed")).isZero();
		jdbc.update("UPDATE store SET naver_place_id='123' WHERE id=1");
		jdbc.update("UPDATE store SET status='DELETED' WHERE id=1");
		assertThat(writer.updateMenuPrice(1,1,"123",200,300)).isZero();
		jdbc.update("UPDATE store SET status='ACTIVE' WHERE id=1");
		jdbc.update("UPDATE menu SET created_by_member_id=7");
		assertThat(writer.updateMenuDescription(1,1,"123","new","changed")).isZero();
		assertThat(jdbc.queryForObject("SELECT description FROM menu WHERE id=1",String.class)).isEqualTo("new");
	}

	@Test void store_writers_do_not_change_unchanged_values_after_the_serving_mapping_is_corrected() {
		jdbc.execute("CREATE TABLE store(id bigint primary key,status text,naver_place_id text,name text,phone text,instagram_handle text,updated_at timestamptz)");
		jdbc.execute("CREATE TABLE store_name_alias(store_id bigint,alias text,created_at timestamptz)");
		jdbc.update("INSERT INTO store VALUES(1,'ACTIVE','changed','old','old','old',CURRENT_TIMESTAMP)");
		var writer=new sweetgirl.dataops.serving.client.ServingWriter(jdbc);
		assertThat(writer.updateStoreName(1,"123","old","new","old")).isZero();
		assertThat(writer.updateStorePhone(1,"123","old","new")).isZero();
		assertThat(writer.updateStoreInstagram(1,"123","old","new")).isZero();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM store_name_alias",Integer.class)).isZero();
		jdbc.update("UPDATE store SET naver_place_id='123'");
		assertThat(writer.updateStoreName(1,"123","old","new","old")).isEqualTo(1);
		assertThat(writer.updateStorePhone(1,"123","old","new")).isEqualTo(1);
		assertThat(writer.updateStoreInstagram(1,"123","old","new")).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM store_name_alias",Integer.class)).isEqualTo(1);
	}

	@Test void exact_menu_update_waits_for_inflight_parent_mapping_change_and_rechecks_it() throws Exception {
		jdbc.execute("CREATE TABLE store(id bigint primary key,status text,naver_place_id text)");
		jdbc.execute("CREATE TABLE menu(id bigint primary key,store_id bigint,price integer,created_by_member_id bigint,deleted_at timestamptz,updated_at timestamptz)");
		jdbc.update("INSERT INTO store VALUES(1,'ACTIVE','123')");
		jdbc.update("INSERT INTO menu(id,store_id,price) VALUES(1,1,100)");
		var worker=Executors.newSingleThreadExecutor();
		try(var editor=jdbc.getDataSource().getConnection()) {
			editor.setAutoCommit(false);
			try(var update=editor.createStatement()) {update.executeUpdate("UPDATE store SET naver_place_id='changed' WHERE id=1");}
			var pending=worker.submit(()->new sweetgirl.dataops.serving.client.ServingWriter(jdbc).updateMenuPrice(1,1,"123",100,200));
			assertThatThrownBy(()->pending.get(150,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
			editor.commit();
			assertThat(pending.get(5,TimeUnit.SECONDS)).isZero();
			assertThat(jdbc.queryForObject("SELECT price FROM menu WHERE id=1",Integer.class)).isEqualTo(100);
		} finally {worker.shutdownNow();}
	}

	@Test void private_writer_namespace_overrides_jdbc_shadow_schema_and_resolves_only_public_tables() {
		jdbc.execute("CREATE TABLE store(id bigint)");jdbc.execute("CREATE TABLE menu(id bigint)");
		jdbc.execute("CREATE TABLE public.store(id bigint)");jdbc.execute("CREATE TABLE public.menu(id bigint)");
		try(var privatePool=new com.zaxxer.hikari.HikariDataSource()) {
			String base=System.getenv("DATAOPS_AGENT_APPLY_TEST_JDBC_URL");
			privatePool.setJdbcUrl(base+(base.contains("?")?"&":"?")+"currentSchema="+schema);
			privatePool.setUsername("postgres");privatePool.setPassword("");privatePool.setMaximumPoolSize(1);
			privatePool.setConnectionInitSql(ServingDatabaseIdentity.WRITE_SEARCH_PATH);
			JdbcTemplate privateJdbc=new JdbcTemplate(privatePool);
			ServingDatabaseIdentity.requirePublicNamespace(privateJdbc);
			privateJdbc.update("INSERT INTO store VALUES(7)");
			assertThat(jdbc.queryForObject("SELECT count(*) FROM public.store",Integer.class)).isEqualTo(1);
			assertThat(jdbc.queryForObject("SELECT count(*) FROM store",Integer.class)).isZero();
		} finally {jdbc.execute("DROP TABLE public.store,public.menu");}
	}

	@Test void plain_claim_guard_distinguishes_immutable_created_and_reused_lineage() throws Exception {
		String sql=sweetgirl.dataops.proposal.repository.ChangeProposalRepository.class
			.getMethod("requiresAgentManifest",Long.class).getAnnotation(org.springframework.data.jpa.repository.Query.class)
			.value().replace(":id","?");
		assertThat(jdbc.queryForObject(sql,Boolean.class,5L)).isFalse();
		// A later provenance row never converts an existing origin into agent-owned authority.
		jdbc.update("INSERT INTO change_proposal VALUES (6,'PENDING')");
		jdbc.update("INSERT INTO response_replay_proposal VALUES (?,'capture',6,'CREATED','{}','REPLAY')",replay);
		assertThat(jdbc.queryForObject(sql,Boolean.class,6L)).isTrue();
	}

	@Test void concurrent_claims_wait_for_the_approval_lock_then_observe_prior_item_outcome() throws Exception {
		var workers=Executors.newFixedThreadPool(2);
		var locked=new CountDownLatch(1);
		var release=new CountDownLatch(1);
		try {
			var first=workers.submit(()->transaction.executeWithoutResult(status->{
				gate.verifyClaim(proposal,request);
				locked.countDown();
				try { if(!release.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("test synchronization timeout"); }
				catch(InterruptedException interrupted) {Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}
				jdbc.update("INSERT INTO change_proposal VALUES (6,'UNKNOWN')");
				jdbc.update("INSERT INTO response_agent_data_apply_item VALUES (?,?,6,?,CURRENT_TIMESTAMP)",approval,request.operationId(),UUID.randomUUID());
			}));
			assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();
			var second=workers.submit(()->transaction.executeWithoutResult(status->gate.verifyClaim(proposal,request)));
			assertThatThrownBy(()->second.get(150,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
			release.countDown();
			first.get(5,TimeUnit.SECONDS);
			assertThatThrownBy(()->second.get(5,TimeUnit.SECONDS)).hasRootCauseMessage("PRIOR_ITEM_REQUIRES_RECONCILIATION");
		} finally {release.countDown();workers.shutdownNow();}
	}
}
