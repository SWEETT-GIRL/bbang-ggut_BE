package sweetgirl.dataops.replay;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import sweetgirl.dataops.reconcile.service.ReconcileService;
import sweetgirl.dataops.snapshot.concurrency.StoreObservationBarrier;
import sweetgirl.dataops.snapshot.parser.BusinessHoursParser;
import sweetgirl.dataops.snapshot.parser.MenuEvidenceParser;
import sweetgirl.dataops.snapshot.parser.NaverPlaceParser;
import sweetgirl.dataops.snapshot.service.ResponseDiagnostics;
import sweetgirl.dataops.snapshot.service.ResponseDigests;
import sweetgirl.dataops.sync.entity.StoreSyncState;
import sweetgirl.dataops.sync.enums.SyncSource;
import sweetgirl.dataops.sync.repository.StoreSyncStateRepository;
import tools.jackson.databind.ObjectMapper;

/** Explicit disposable local DB only; each test owns a fresh schema and never touches public tables. */
@EnabledIfEnvironmentVariable(named = "DATAOPS_REPLAY_TEST_JDBC_URL", matches = "jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/agent_replay_test")
class JdbcReplayJournalPostgresTest {
	final ObjectMapper mapper = new ObjectMapper();
	final NaverPlaceParser parser = new NaverPlaceParser();
	final ResponseDiagnostics diagnostics = new ResponseDiagnostics(mapper, parser, new BusinessHoursParser(), new MenuEvidenceParser(mapper));
	final ReconcileService reconcile = mock(ReconcileService.class);
	final String hash = "a".repeat(64);
	JdbcTemplate admin, jdbc;
	TransactionTemplate tx;
	JdbcReplayJournal journal;
	CaptureReplayService service;
	ReplayPlan plan;
	String schema;

	@BeforeEach void setup() throws Exception {
		String url = System.getenv("DATAOPS_REPLAY_TEST_JDBC_URL");
		admin = new JdbcTemplate(new DriverManagerDataSource(url, "postgres", ""));
		schema = "replay_" + UUID.randomUUID().toString().replace("-", "");
		admin.execute("CREATE SCHEMA " + schema);
		var ds = new DriverManagerDataSource(url + "?currentSchema=" + schema, "postgres", "");
		jdbc = new JdbcTemplate(ds);
		jdbc.execute(Files.readString(Path.of("src/main/resources/db/dataops-migration/V20261013100000__response_capture_and_incident.sql")));
		jdbc.execute("CREATE TABLE change_proposal(id BIGINT PRIMARY KEY, origin TEXT)");
		jdbc.execute("CREATE TABLE place_snapshot(id BIGINT PRIMARY KEY)");
		jdbc.execute(Files.readString(Path.of("src/main/resources/db/dataops-migration/V20261014180000__agent_capture_replay.sql")));
		var manager = new DataSourceTransactionManager(ds);
		tx = new TransactionTemplate(manager);
		journal = new JdbcReplayJournal(jdbc, mapper);
		String raw = CaptureReplayServiceTest.raw(mapper);
		OffsetDateTime observed = OffsetDateTime.now().minusMinutes(1);
		jdbc.update("""
			INSERT INTO response_capture(capture_id,request_digest,store_id,source,external_id,observed_at,received_at,
			 attempt_seq,payload,payload_digest,status,parser_digest,contract_version)
			VALUES ('capture',?,1,'NAVER','123',?,?,0,?,?,'HELD','old-parser','old-contract')
			""", hash, observed, observed.plusSeconds(5), raw, ResponseDigests.sha256(raw));
		String expected = ResponseDigests.sha256(ResponseDigests.canonical(mapper.valueToTree(parser.parse(mapper.readTree(raw))), mapper));
		// PostgreSQL keeps microsecond timestamps; bind approved provenance to the stored value.
		var stored = journal.capture("capture").orElseThrow();
		var target = new ReplayPlan.Target("capture", stored.payloadDigest(), 1, "123", stored.observedAt().toInstant(), 0L, expected);
		plan = new ReplayPlan(UUID.randomUUID(), hash, "release", hash, diagnostics.runtimeParserDigest(),
			diagnostics.structureProfile().digest(), ResponseDiagnostics.CONTRACT, hash, Instant.now().plusSeconds(300), List.of(target), List.of("capture"));
		@SuppressWarnings("unchecked") ObjectProvider<VerifiedReplayAuthority> providers = mock(ObjectProvider.class);
		VerifiedReplayAuthority authority = mock(VerifiedReplayAuthority.class);
		when(providers.getIfAvailable()).thenReturn(authority);
		when(authority.require(any(), anyString())).thenAnswer(call -> plan);
		when(authority.currentRuntime()).thenReturn(new VerifiedReplayAuthority.RuntimeIdentity("release", hash));
		when(reconcile.replayPolicyDigest()).thenReturn(hash);
		StoreSyncStateRepository states = mock(StoreSyncStateRepository.class);
		when(states.findForSnapshotUpdate(1L, SyncSource.NAVER)).thenAnswer(call -> Optional.of(StoreSyncState.builder()
			.storeId(1L).source(SyncSource.NAVER).externalId("123").build()));
		service = new CaptureReplayService(providers, journal, new StoreObservationBarrier(ds, ds), states, diagnostics, parser, reconcile, mapper, manager);
	}
	@AfterEach void cleanup() { if (admin != null && schema != null) admin.execute("DROP SCHEMA " + schema + " CASCADE"); }

	@Test void committed_receipt_is_reused_without_touching_original_capture_or_snapshot_history() {
		String before = jdbc.queryForObject("SELECT row_to_json(c)::text FROM response_capture c", String.class);
		when(reconcile.reconcileCapturedReplay(any(), any(), anyBoolean())).thenAnswer(call -> {
			jdbc.update("INSERT INTO change_proposal VALUES (42,'original-replay-generation')");
			return List.of(proposal(42, true));
		});
		assertThat(service.canary(plan.operationId(), hash).passed()).isTrue();
		var first = service.replay(plan.operationId(), hash);
		assertThat(service.replay(plan.operationId(), hash)).isEqualTo(first);
		assertThat(jdbc.queryForObject("SELECT row_to_json(c)::text FROM response_capture c", String.class)).isEqualTo(before);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM place_snapshot", Integer.class)).isZero();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM response_replay_receipt", Integer.class)).isEqualTo(2);
		assertThat(jdbc.queryForObject("SELECT relation FROM response_replay_proposal", String.class)).isEqualTo("CREATED");
		verify(reconcile, times(1)).reconcileCapturedReplay(any(), any(), anyBoolean());
	}
	@Test void a_failure_after_proposal_insert_rolls_back_both_and_safe_retry_uses_same_targets() {
		AtomicInteger attempts = new AtomicInteger();
		when(reconcile.reconcileCapturedReplay(any(), any(), anyBoolean())).thenAnswer(call -> {
			jdbc.update("INSERT INTO change_proposal VALUES (42,'same-operation')");
			if (attempts.incrementAndGet() == 1) throw new IllegalStateException("injected failure before receipt");
			return List.of(proposal(42, true));
		});
		service.canary(plan.operationId(), hash);
		assertThatThrownBy(() -> service.replay(plan.operationId(), hash)).hasMessage("injected failure before receipt");
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM change_proposal", Integer.class)).isZero();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM response_replay_receipt WHERE phase='REPLAY'", Integer.class)).isZero();
		assertThat(service.replay(plan.operationId(), hash).passed()).isTrue();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM change_proposal", Integer.class)).isEqualTo(1);
	}
	@Test void concurrent_same_operation_creates_exactly_one_proposal_and_receipt() throws Exception {
		when(reconcile.reconcileCapturedReplay(any(), any(), anyBoolean())).thenAnswer(call -> {
			jdbc.update("INSERT INTO change_proposal VALUES (42,'one-generation')");
			return List.of(proposal(42, true));
		});
		service.canary(plan.operationId(), hash);
		var executor = Executors.newFixedThreadPool(2);
		try {
			var a = executor.submit(() -> service.replay(plan.operationId(), hash));
			var b = executor.submit(() -> service.replay(plan.operationId(), hash));
			assertThat(a.get(5, TimeUnit.SECONDS)).isEqualTo(b.get(5, TimeUnit.SECONDS));
		} finally { executor.shutdownNow(); }
		verify(reconcile, times(1)).reconcileCapturedReplay(any(), any(), anyBoolean());
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM response_replay_proposal", Integer.class)).isEqualTo(1);
	}
	@Test void later_held_raw_prevents_old_capture_replay_and_never_becomes_absence_visit() {
		service.canary(plan.operationId(), hash);
		jdbc.update("""
			INSERT INTO response_capture(capture_id,request_digest,store_id,source,external_id,observed_at,received_at,payload,payload_digest,status)
			SELECT 'newer',request_digest,store_id,source,external_id,observed_at+INTERVAL '1 second',received_at+INTERVAL '1 second',payload,payload_digest,'HELD'
			FROM response_capture WHERE capture_id='capture'
			""");
		var result = service.replay(plan.operationId(), hash);
		assertThat(result.passed()).isFalse();
		assertThat(result.items().get(0).status()).isEqualTo("STALE");
		verify(reconcile, never()).reconcileCapturedReplay(any(), any(), anyBoolean());
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM place_snapshot", Integer.class)).isZero();
	}
	@Test void replay_history_is_immutable_and_new_operation_cannot_repeat_same_capture_release_policy() {
		service.canary(plan.operationId(), hash);
		assertThatThrownBy(() -> jdbc.update("DELETE FROM response_replay_receipt")).hasMessageContaining("immutable");
		assertThatThrownBy(() -> jdbc.update("UPDATE response_replay_run SET release_id='forged'")).hasMessageContaining("immutable");
		ReplayPlan changed = new ReplayPlan(UUID.randomUUID(), hash, plan.releaseId(), hash, plan.parserDigest(), plan.profileDigest(),
			plan.contractVersion(), hash, plan.expiresAt(), plan.targets(), plan.canaryCaptureIds());
		assertThatThrownBy(() -> tx.executeWithoutResult(status -> journal.begin(changed))).hasMessageContaining("duplicate key");
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM response_replay_run", Integer.class)).isEqualTo(1);
	}
	private ReconcileService.ReplayProposal proposal(long id, boolean created) {
		return new ReconcileService.ReplayProposal(id, created, "NAVER_SNAPSHOT_RECONCILE", null, UUID.randomUUID(), "store.name", "old", "new", null);
	}
}
