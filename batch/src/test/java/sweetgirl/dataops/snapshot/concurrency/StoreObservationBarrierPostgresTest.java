package sweetgirl.dataops.snapshot.concurrency;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import com.zaxxer.hikari.HikariDataSource;
import sweetgirl.dataops.proposal.entity.ChangeProposal;
import sweetgirl.dataops.proposal.exception.ProposalException;
import sweetgirl.dataops.proposal.policy.LocationProposalGate;
import sweetgirl.dataops.proposal.policy.MenuRemovalProposalGate;
import sweetgirl.dataops.proposal.repository.ChangeProposalRepository;
import sweetgirl.dataops.proposal.service.ProposalApplyStateService;
import sweetgirl.dataops.proposal.service.ProposalCommandService;
import sweetgirl.dataops.reconcile.enums.ProvenanceSource;
import sweetgirl.dataops.reconcile.policy.MenuCategoryClassifier;
import sweetgirl.dataops.serving.client.ServingReader;
import sweetgirl.dataops.serving.client.ServingWriter;
import sweetgirl.dataops.serving.dto.MenuCurrentValues;
import sweetgirl.dataops.serving.service.ApplyLogCommandService;
import sweetgirl.dataops.snapshot.dto.request.SnapshotSubmitRequest;
import sweetgirl.dataops.snapshot.entity.PlaceSnapshot;
import sweetgirl.dataops.snapshot.parser.MenuEvidenceParser;
import sweetgirl.dataops.snapshot.parser.NaverPlaceParser;
import sweetgirl.dataops.snapshot.repository.MenuEvidenceRepository;
import sweetgirl.dataops.snapshot.repository.PlaceSnapshotRepository;
import sweetgirl.dataops.snapshot.service.ResponseCaptureService;
import sweetgirl.dataops.snapshot.service.ResponseDiagnostics;
import sweetgirl.dataops.snapshot.service.ResponseDigests;
import sweetgirl.dataops.sync.enums.SyncSource;
import tools.jackson.databind.ObjectMapper;

/**
 * Opt-in disposable PostgreSQL test. Uses production capture, gate, barrier, approval orchestration
 * and ServingWriter SQL. The proposal repository/state adapter is test-only; its claim/log/settle
 * operations use real independently committed JDBC transactions to check ordering and pool use.
 * DATAOPS_BARRIER_TEST_JDBC_URL must name a disposable, local PostgreSQL database.
 */
@EnabledIfEnvironmentVariable(named = "DATAOPS_BARRIER_TEST_JDBC_URL", matches = "jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/.*")
class StoreObservationBarrierPostgresTest {
	private final ObjectMapper mapper = new ObjectMapper();
	private final OffsetDateTime observed = OffsetDateTime.now().minusMinutes(2);
	private final ExecutorService executor = Executors.newSingleThreadExecutor();
	private final AtomicReference<Runnable> afterReceive = new AtomicReference<>(() -> {});
	private final AtomicReference<Runnable> afterAssess = new AtomicReference<>(() -> {});
	private HikariDataSource dataSource;
	private DriverManagerDataSource direct;
	private JdbcTemplate jdbc;
	private String schema;
	private StoreObservationBarrier barrier;
	private ResponseCaptureService captures;
	private ProposalCommandService approvals;
	private ServingWriter writer;
	private ProposalApplyStateService state;
	private TransactionTemplate transactions;
	private ChangeProposal proposal;

	@BeforeEach void setup() throws Exception {
		String baseUrl = System.getenv("DATAOPS_BARRIER_TEST_JDBC_URL");
		var admin = new JdbcTemplate(new DriverManagerDataSource(baseUrl, "postgres", ""));
		schema = "barrier_" + UUID.randomUUID().toString().replace("-", "");
		admin.execute("CREATE SCHEMA " + schema);
		String url = baseUrl + (baseUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema;
		direct = new DriverManagerDataSource(url, "postgres", "");
		dataSource = new HikariDataSource();
		dataSource.setJdbcUrl(url);
		dataSource.setUsername("postgres");
		dataSource.setMaximumPoolSize(1);
		dataSource.setConnectionTimeout(1000);
		jdbc = new JdbcTemplate(dataSource);
		jdbc.execute(Files.readString(Path.of("src/main/resources/db/dataops-migration/V20261013100000__response_capture_and_incident.sql")));
		jdbc.execute("CREATE TABLE store_sync_state (store_id BIGINT, source TEXT, external_id TEXT, content_hash VARCHAR(64))");
		jdbc.execute(Files.readString(Path.of("src/main/resources/db/dataops-migration/V20261013233000__response_menu_scope.sql")));
		jdbc.execute("""
			CREATE TABLE menu_evidence_observation (id BIGSERIAL PRIMARY KEY, observation_id TEXT, store_id BIGINT,
			 source TEXT, external_id TEXT, observed_at TIMESTAMP, payload_hash TEXT, menu_complete BOOLEAN, identity_verified BOOLEAN)
			""");
		jdbc.execute("CREATE TABLE menu (id BIGINT PRIMARY KEY, name TEXT, deleted_at TIMESTAMP, updated_at TIMESTAMP, created_by_member_id BIGINT)");
		jdbc.execute("CREATE TABLE test_proposal_state (id BIGINT PRIMARY KEY, status TEXT)");
		jdbc.execute("CREATE TABLE test_apply_log (proposal_id BIGINT)");
		jdbc.update("INSERT INTO store_sync_state (store_id,source,external_id) VALUES (1,'NAVER','123')");
		jdbc.update("INSERT INTO menu (id,name) VALUES (77,'소금빵')");
		jdbc.update("INSERT INTO test_proposal_state VALUES (5,'PENDING')");
		barrier = new StoreObservationBarrier(dataSource);
		var manager = new DataSourceTransactionManager(dataSource);
		transactions = new TransactionTemplate(manager);
		var factory = new ProxyFactory(new ResponseCaptureService(dataSource, mapper, barrier));
		factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
		factory.addAdvice((MethodInterceptor) invocation -> {
			Object result = invocation.proceed();
			if (invocation.getMethod().getName().equals("receive")) afterReceive.get().run();
			if (invocation.getMethod().getName().equals("assess")) afterAssess.get().run();
			return result;
		});
		captures = (ResponseCaptureService) factory.getProxy();
		seedCompleteCapture();
		buildApprovalService();
	}

	private void seedCompleteCapture() {
		captures.receive(request("complete", observed));
		captures.assess("complete", "NAVER", new ResponseDiagnostics.Report("parser", List.of()));
		String hash = ResponseDigests.sha256(ResponseDigests.canonical(mapper.readTree(raw()), mapper));
		jdbc.update("INSERT INTO menu_evidence_observation (observation_id,store_id,source,external_id,observed_at,payload_hash,menu_complete,identity_verified) VALUES ('complete',1,'NAVER','123',?,?,true,true)",
			observed.atZoneSameInstant(ZoneId.of("Asia/Seoul")).toLocalDateTime(), hash);
	}

	@SuppressWarnings("unchecked")
	private void buildApprovalService() {
		proposal = ChangeProposal.builder().storeId(1L).target("menu.77.removed")
			.oldValue("{\"name\":\"소금빵\"}").source(ProvenanceSource.NAVER).snapshotId(10L).build();
		ReflectionTestUtils.setField(proposal, "id", 5L);
		var proposals = mock(ChangeProposalRepository.class);
		when(proposals.findById(5L)).thenReturn(Optional.of(proposal));
		var snapshots = mock(PlaceSnapshotRepository.class);
		when(snapshots.findById(10L)).thenReturn(Optional.of(PlaceSnapshot.builder().storeId(1L).source(SyncSource.NAVER)
			.fetchedAt(observed.minusMinutes(1).atZoneSameInstant(ZoneId.of("Asia/Seoul")).toLocalDateTime()).payload(raw()).build()));
		var gate = new MenuRemovalProposalGate(new MenuEvidenceRepository(dataSource), snapshots,
			new MenuEvidenceParser(mapper), new NaverPlaceParser(), mapper);
		var reader = mock(ServingReader.class);
		when(reader.findMenus(1L)).thenReturn(List.of(new MenuCurrentValues(77L,"소금빵",3500,null,false,null,null)));
		when(reader.findStoreByNaverPlaceId("123")).thenReturn(Optional.of(new ServingReader.StoreRef(1,"ACTIVE")));
		ObjectProvider<ServingReader> readers = mock(ObjectProvider.class);
		when(readers.getIfAvailable()).thenReturn(reader);
		writer = spy(new ServingWriter(new JdbcTemplate(direct)));
		ObjectProvider<ServingWriter> writers = mock(ObjectProvider.class);
		when(writers.getIfAvailable()).thenReturn(writer);
		state = mock(ProposalApplyStateService.class);
		when(state.claim(5L)).thenAnswer(call -> transactions.execute(tx -> {
			assertThat(jdbc.update("UPDATE test_proposal_state SET status='APPLYING' WHERE id=5 AND status='PENDING'")).isEqualTo(1);
			UUID id = UUID.randomUUID();
			proposal.beginApply(id);
			return id;
		}));
		doAnswer(call -> { transactions.executeWithoutResult(tx -> {
			jdbc.update("UPDATE test_proposal_state SET status='APPROVED' WHERE id=5"); proposal.completeApply(null);
		}); return null; }).when(state).settleApplied(eq(5L), any(), any());
		doAnswer(call -> { transactions.executeWithoutResult(tx -> {
			jdbc.update("UPDATE test_proposal_state SET status='UNKNOWN' WHERE id=5"); proposal.markApplyUnknown("INTERRUPTED", "test");
		}); return null; }).when(state).settleUnknown(eq(5L), any(), any());
		var logs = mock(ApplyLogCommandService.class);
		doAnswer(call -> { transactions.executeWithoutResult(tx -> jdbc.update("INSERT INTO test_apply_log VALUES (5)")); return null; })
			.when(logs).recordBeforeApply(any(), any(), any(), any(), any(), any(), any());
		approvals = new ProposalCommandService(proposals, state, logs, writers, readers,
			mock(LocationProposalGate.class), gate, barrier, mapper, new MenuCategoryClassifier());
	}

	@AfterEach void cleanup() throws Exception {
		executor.shutdownNow();
		assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
		if (dataSource != null) dataSource.close();
		if (direct != null) new JdbcTemplate(direct).execute("DROP SCHEMA " + schema + " CASCADE");
	}

	@Test void receive_first_is_invisible_before_commit_but_blocks_approval_then_received_raw_holds_it() throws Exception {
		var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
		afterReceive.set(() -> pause(entered, release));
		var pending = executor.submit(() -> captures.receive(request("new", observed.plusSeconds(1))));
		assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
		try {
			assertThatThrownBy(() -> approvals.approve(5L)).isInstanceOf(CannotAcquireLockException.class);
			assertUnwrittenDirect();
		} finally { release.countDown(); }
		pending.get(5, TimeUnit.SECONDS);
		afterReceive.set(() -> {});
		assertThatThrownBy(() -> approvals.approve(5L)).isInstanceOf(ProposalException.class).hasMessageContaining("미판정");
		assertUnwrittenDirect();
	}

	@Test void reassessment_first_cannot_expose_previously_ready_capture_during_uncommitted_hold() throws Exception {
		var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
		afterAssess.set(() -> pause(entered, release));
		var pending = executor.submit(() -> captures.assess("complete", "NAVER",
			new ResponseDiagnostics.Report("parser", List.of(new ResponseDiagnostics.Issue("price","TYPE","OBJECT",true)))));
		assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
		try {
			assertThatThrownBy(() -> approvals.approve(5L)).isInstanceOf(CannotAcquireLockException.class);
			assertUnwrittenDirect();
		} finally { release.countDown(); }
		pending.get(5, TimeUnit.SECONDS);
		assertThatThrownBy(() -> approvals.approve(5L)).isInstanceOf(ProposalException.class);
		assertUnwrittenDirect();
	}

	@Test void approval_first_blocks_receive_and_assess_through_serving_write_then_retry_succeeds() throws Exception {
		var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
		doAnswer(call -> {
			assertThat(new JdbcTemplate(direct).queryForObject("SELECT status FROM test_proposal_state WHERE id=5", String.class)).isEqualTo("APPLYING");
			assertThat(new JdbcTemplate(direct).queryForObject("SELECT count(*) FROM test_apply_log", Integer.class)).isEqualTo(1);
			pause(entered, release);
			return call.callRealMethod();
		}).when(writer).softDeleteMenu(77L, "소금빵");
		var pending = executor.submit(() -> approvals.approve(5L));
		assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
		try {
			assertThatThrownBy(() -> captures.receive(request("after", observed.plusSeconds(1)))).isInstanceOf(CannotAcquireLockException.class);
			assertThatThrownBy(() -> captures.assess("complete", "NAVER", new ResponseDiagnostics.Report("parser", List.of()))).isInstanceOf(CannotAcquireLockException.class);
			assertThat(jdbc.queryForObject("SELECT count(*) FROM response_capture WHERE capture_id='after'", Integer.class)).isZero();
		} finally { release.countDown(); }
		pending.get(5, TimeUnit.SECONDS);
		assertThat(jdbc.queryForObject("SELECT status FROM test_proposal_state WHERE id=5", String.class)).isEqualTo("APPROVED");
		assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM menu WHERE id=77", Boolean.class)).isTrue();
		assertThat(captures.receive(request("after", observed.plusSeconds(1)))).isEqualTo("after");
	}

	@Test void failed_capture_rolls_back_and_releases_lock_but_prior_independent_raw_survives() {
		afterReceive.set(() -> { throw new IllegalStateException("after insert, before commit"); });
		assertThatThrownBy(() -> captures.receive(request("rollback", observed.plusSeconds(1)))).isInstanceOf(IllegalStateException.class);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM response_capture", Integer.class)).isEqualTo(1);
		try (var scope = barrier.lockApproval(1)) { scope.assertHeld(); }
	}

	@Test void writer_failure_releases_lock_and_keeps_unknown_result() {
		doThrow(new IllegalStateException("writer interrupted")).when(writer).softDeleteMenu(77L,"소금빵");
		assertThatThrownBy(() -> approvals.approve(5L)).isInstanceOf(ProposalException.class);
		assertThat(jdbc.queryForObject("SELECT status FROM test_proposal_state WHERE id=5",String.class)).isEqualTo("UNKNOWN");
		assertThat(captures.receive(request("retry",observed.plusSeconds(1)))).isEqualTo("retry");
	}

	@Test void dead_barrier_connection_is_detected_before_serving_dml_and_lock_is_released() {
		doAnswer(call -> {
			UUID operation = UUID.randomUUID();
			transactions.executeWithoutResult(tx -> {
				jdbc.update("UPDATE test_proposal_state SET status='APPLYING' WHERE id=5");
				proposal.beginApply(operation);
			});
			new JdbcTemplate(direct).queryForList("SELECT pg_terminate_backend(pid) FROM pg_locks WHERE locktype='advisory' AND classid=2147483648 AND objid=1 AND objsubid=1");
			return operation;
		}).when(state).claim(5L);
		assertThatThrownBy(() -> approvals.approve(5L)).isInstanceOf(ProposalException.class);
		verify(writer, never()).softDeleteMenu(anyLong(), any());
		try (var scope = barrier.lockApproval(1)) { scope.assertHeld(); }
	}

	@Test void lock_on_another_store_does_not_block_capture_and_autocommit_use_is_rejected() {
		try (var scope = barrier.lockApproval(2)) {
			assertThat(captures.receive(request("different", observed.plusSeconds(1)))).isEqualTo("different");
		}
		assertThatThrownBy(() -> barrier.lockObservation(1)).isInstanceOf(IllegalStateException.class);
	}

	private void assertUnwrittenDirect() {
		var audit = new JdbcTemplate(direct);
		assertThat(audit.queryForObject("SELECT status FROM test_proposal_state WHERE id=5", String.class)).isEqualTo("PENDING");
		assertThat(audit.queryForObject("SELECT count(*) FROM test_apply_log", Integer.class)).isZero();
		assertThat(audit.queryForObject("SELECT deleted_at IS NULL FROM menu WHERE id=77", Boolean.class)).isTrue();
	}
	private static void pause(CountDownLatch entered, CountDownLatch release) {
		entered.countDown();
		try { if (!release.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("test latch timeout"); }
		catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
	}
	private SnapshotSubmitRequest request(String id, OffsetDateTime at) {
		return new SnapshotSubmitRequest(1L,SyncSource.NAVER,mapper.readTree(raw()),null,id,"123",at,1L);
	}
	private String raw() {
		var raw = mapper.createObjectNode();
		var root = raw.putObject("ROOT_QUERY").putObject("placeDetail({\"input\":{\"id\":\"123\"}})");
		root.put("id", "123");
		root.putObject("base").put("__ref", "PlaceDetailBase:123");
		root.putObject("placeMenus").put("total", 0).putArray("items");
		raw.putObject("PlaceDetailBase:123").put("id", "123").put("name", "가게");
		return raw.toString();
	}
}
