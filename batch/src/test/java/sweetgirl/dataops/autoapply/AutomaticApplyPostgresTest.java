package sweetgirl.dataops.autoapply;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.Arrays;
import java.util.stream.Stream;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import sweetgirl.dataops.snapshot.service.ResponseDigests;
import sweetgirl.dataops.reconcile.policy.BeverageFilter;
import sweetgirl.dataops.reconcile.policy.MergePolicy;
import sweetgirl.dataops.reconcile.policy.MenuCategoryClassifier;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import sweetgirl.dataops.proposal.entity.ChangeProposal;
import sweetgirl.dataops.proposal.enums.ProposalProducer;
import sweetgirl.dataops.proposal.repository.ChangeProposalRepository;
import sweetgirl.dataops.proposal.service.ProposalCommandService;
import sweetgirl.dataops.reconcile.enums.ProvenanceSource;
import sweetgirl.dataops.reconcile.repository.StoreFieldProvenanceRepository;
import sweetgirl.dataops.reconcile.service.ReconcileService;
import sweetgirl.dataops.serving.client.ServingWriter;
import sweetgirl.dataops.snapshot.concurrency.StoreObservationBarrier;
import sweetgirl.dataops.snapshot.parser.NaverPlaceParser;
import sweetgirl.dataops.snapshot.service.ResponseDiagnostics;
import tools.jackson.databind.ObjectMapper;

@EnabledIfEnvironmentVariable(named = "DATAOPS_AUTO_APPLY_TEST_JDBC_URL", matches = "jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/.*")
class AutomaticApplyPostgresTest {
	private JdbcTemplate admin, jdbc;
	private DriverManagerDataSource source;
	private ObjectMapper mapper;
	private ChangeProposalRepository repository;
	private AutomaticApplyWriter writer;
	private String schema;
	private AutomaticApplyCommandService service;
	private ProposalCommandService commands;
	private ReconcileService reconcile;
	private ChangeProposal proposal;
	private ServingWriter serving;
	private ResponseDiagnostics diagnostics;
	private StoreFieldProvenanceRepository provenance;
	@BeforeEach void setup() throws Exception {
		String base = System.getenv("DATAOPS_AUTO_APPLY_TEST_JDBC_URL");
		admin = new JdbcTemplate(new DriverManagerDataSource(base, "postgres", ""));
		schema = "automatic_" + UUID.randomUUID().toString().replace("-", "");
		admin.execute("CREATE SCHEMA " + schema);
		source = new DriverManagerDataSource(base + "?currentSchema=" + schema, "postgres", "");
		jdbc = new JdbcTemplate(source);
		jdbc.execute("CREATE TABLE response_capture(capture_id varchar(64) primary key, store_id bigint, source text, external_id text, payload text, observed_at timestamptz, received_at timestamptz default now(), attempt_seq bigint, target_verified boolean, status text, processed_response text)");
		jdbc.execute(Files.readString(Path.of("src/main/resources/db/dataops-migration/V20261127010000__automatic_capture_apply.sql")));
		jdbc.execute("CREATE TABLE store(id bigint primary key, naver_place_id text, status text)");
		jdbc.execute("CREATE TABLE menu_category(id bigint primary key, name text)");
		jdbc.execute("CREATE TABLE menu(id bigint generated always as identity primary key, store_id bigint references store(id), name text, normalized_name text, price integer, description varchar(255), menu_category_id bigint, like_count bigint, created_at timestamp default now(), updated_at timestamp default now(), deleted_at timestamp, created_by_member_id bigint)");
		jdbc.execute("CREATE UNIQUE INDEX unique_menu ON menu(store_id, normalized_name) WHERE deleted_at IS NULL");
		jdbc.execute("INSERT INTO store VALUES(1,'123','ACTIVE')");
		jdbc.execute("INSERT INTO menu(store_id,name,normalized_name,price) VALUES(1,'소금빵','소금빵',3000)");
		capture("current", "READY", true);
		mapper = new ObjectMapper();
		diagnostics = mock(ResponseDiagnostics.class);
		when(diagnostics.inspect(any())).thenReturn(new ResponseDiagnostics.Report("parser", List.of()));
		reconcile = mock(ReconcileService.class);
		when(reconcile.reconcileAutomatic(eq(1L), any(), any(), anyBoolean(), anyBoolean()))
			.thenReturn(List.of(new AutomaticCandidate(7, "menu.1.price", "3000", "3500", ProvenanceSource.NAVER)));
		proposal = ChangeProposal.builder().storeId(1L).target("menu.1.price").oldValue("3000").newValue("3500")
			.source(ProvenanceSource.NAVER).producer(ProposalProducer.NAVER_SNAPSHOT_RECONCILE).build();
		ReflectionTestUtils.setField(proposal, "id", 7L);
		repository = mock(ChangeProposalRepository.class);
		when(repository.findById(7L)).thenAnswer(invocation -> Optional.of(proposal));
		commands = mock(ProposalCommandService.class);
		serving = new ServingWriter(jdbc);
		writer = mock(AutomaticApplyWriter.class);
		when(writer.writer()).thenReturn(serving);
		when(commands.approveAutomatically(eq(7L), eq(serving), any(), any())).thenAnswer(invocation -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM response_auto_apply", Integer.class)).isEqualTo(1);
			ProposalCommandService.AutomaticApproval approval = invocation.getArgument(3);
			approval.verify().accept(proposal);
			proposal.beginApply(UUID.randomUUID());
			assertThat(serving.updateMenuPrice(1, 1, approval.externalId(), 3000, 3500)).isEqualTo(1);
			proposal.completeApply(null);
			return null;
		});
		provenance = mock(StoreFieldProvenanceRepository.class);
		service = newService();
	}
	private AutomaticApplyCommandService newService() {
		var queries = transactional(new AutomaticApplyQueryService(source, mapper, repository, provenance));
		return transactional(new AutomaticApplyCommandService(source, new DataSourceTransactionManager(source), mapper,
			new StoreObservationBarrier(source, source), diagnostics, new NaverPlaceParser(), reconcile,
			queries, new AutomaticApplyPolicy(mapper), commands, writer));
	}

	@SuppressWarnings("unchecked")
	private <T> T transactional(T target) {
		var proxy = new ProxyFactory(target);
		proxy.setProxyTargetClass(true);
		proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source), new AnnotationTransactionAttributeSource()));
		return (T)proxy.getProxy();
	}

	static Stream<Class<?>> changedPolicies() {
		return Stream.of(BeverageFilter.class, MergePolicy.class, MenuCategoryClassifier.class);
	}

	@ParameterizedTest
	@MethodSource("changedPolicies")
	void changed_menu_policy_blocks_an_unfinished_saved_plan(Class<?> changedClass) {
		when(commands.approveAutomatically(anyLong(), any(), any(), any()))
			.thenThrow(new IllegalStateException("interrupted before claim"));
		assertThatThrownBy(() -> service.apply("current")).hasMessage("interrupted before claim");
		assertThat(jdbc.queryForObject("SELECT results::text FROM response_auto_apply", String.class)).isEqualTo("{}");
		try (var digests = mockStatic(ResponseDigests.class, CALLS_REAL_METHODS)) {
			digests.when(() -> ResponseDigests.classes(any(Class[].class))).thenAnswer(invocation -> {
				Class<?>[] measured = (Class<?>[])invocation.getRawArguments()[0];
				String original = (String)invocation.callRealMethod();
				return Arrays.asList(measured).contains(changedClass) ? original + ":changed-policy-bytecode" : original;
			});
			service = newService();
		}
		var result = service.apply("current");
		assertThat(result.held()).isEqualTo(1);
		assertThat(result.items().get(0).reason()).isEqualTo("POLICY_CHANGED");
		assertThat(jdbc.queryForObject("SELECT price FROM menu WHERE id=1", Integer.class)).isEqualTo(3000);
		verify(commands, times(1)).approveAutomatically(anyLong(), any(), any(), any());
	}

	@Test void missing_capture_and_plan_queries_do_not_guess_an_observation() {
		var queries = transactional(new AutomaticApplyQueryService(source, mapper, repository, provenance));
		assertThat(queries.findPlan("missing")).isEmpty();
		assertThatThrownBy(() -> queries.findCapture("missing"))
			.isInstanceOf(AutomaticApplyException.class).hasMessage("CAPTURE_MISSING");
		assertThatThrownBy(() -> queries.findProposal(999))
			.isInstanceOf(AutomaticApplyException.class).hasMessage("PROPOSAL_MISSING");
	}

	@AfterEach void cleanup() { admin.execute("DROP SCHEMA " + schema + " CASCADE"); }
	private void capture(String id, String status, boolean complete) {
		jdbc.update("INSERT INTO response_capture(capture_id,store_id,source,external_id,payload,observed_at,target_verified,status,processed_response) VALUES(?,1,'NAVER','123','{}',now(),true,?,?)",
			id, status, complete ? "{\"assessment\":\"" + status + "\",\"snapshotId\":10}" : null);
	}
	@Test void applies_and_replays_without_another_write() {
		assertThat(service.apply("current").applied()).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT price FROM menu WHERE id=1", Integer.class)).isEqualTo(3500);
		assertThat(service.apply("current").alreadyApplied()).isEqualTo(1);
		verify(commands, times(1)).approveAutomatically(anyLong(), any(), any(), any());
		assertThat(jdbc.queryForObject("SELECT results->'7'->>'status' FROM response_auto_apply", String.class)).isEqualTo("APPLIED");
	}
    @Test void outer_transaction_rollback_does_not_undo_the_committed_apply_plan_or_serving_write() {
        var outer = new org.springframework.transaction.support.TransactionTemplate(new DataSourceTransactionManager(source));
        outer.executeWithoutResult(status -> {
            assertThat(service.apply("current").applied()).isEqualTo(1);
            status.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT price FROM menu WHERE id=1", Integer.class)).isEqualTo(3500);
        assertThat(jdbc.queryForObject("SELECT results->'7'->>'status' FROM response_auto_apply", String.class)).isEqualTo("APPLIED");
    }

	@Test void newer_unassessed_response_blocks_old_automatic_apply() {
		capture("newer", "RECEIVED", false);
		assertThat(service.apply("current").observationHold()).isEqualTo("STALE_OBSERVATION");
		verifyNoInteractions(commands);
	}
	@Test void unchanged_capture_still_reconciles_pending_changes_against_current_serving_values() {
		jdbc.update("UPDATE response_capture SET processed_response='{\"assessment\":\"READY\",\"changed\":false,\"snapshotId\":null}'");
		assertThat(service.apply("current").applied()).isEqualTo(1);
		verify(reconcile).reconcileAutomatic(eq(1L), isNull(), any(), eq(true), eq(false));
	}
	@Test void partial_naver_menus_can_apply_verified_same_source_baemin() {
		jdbc.update("UPDATE response_capture SET status='PARTIAL', processed_response='{\"assessment\":\"PARTIAL\",\"snapshotId\":10}'");
		when(diagnostics.inspect(any())).thenReturn(new ResponseDiagnostics.Report("parser", List.of(
			new ResponseDiagnostics.Issue("placeDetail.menus", "MENU_INCOMPLETE", "null", false)), true));
		when(reconcile.reconcileAutomatic(eq(1L), any(), any(), eq(false), eq(true)))
			.thenReturn(List.of(new AutomaticCandidate(7, "menu.1.price", "3000", "3500", ProvenanceSource.BAEMIN)));
		ReflectionTestUtils.setField(proposal, "source", ProvenanceSource.BAEMIN);
		when(provenance.findByStoreId(1L)).thenReturn(List.of(sweetgirl.dataops.reconcile.entity.StoreFieldProvenance.builder()
			.storeId(1L).field("menu.1").source(ProvenanceSource.BAEMIN).build()));
		assertThat(service.apply("current").applied()).isEqualTo(1);
	}
	@Test void parser_structure_change_is_held_before_any_plan() {
		when(diagnostics.inspect(any())).thenReturn(new ResponseDiagnostics.Report("parser", List.of(
			new ResponseDiagnostics.Issue("menus", "MENU_CONTAINER_MISSING", "missing", true))));
		assertThat(service.apply("current").observationHold()).isEqualTo("STRUCTURE_CHANGED");
		verifyNoInteractions(reconcile, commands);
	}
	@Test void held_capture_never_creates_a_plan_or_writes() {
		jdbc.update("UPDATE response_capture SET status='HELD', processed_response=NULL");
		assertThat(service.apply("current").observationHold()).isEqualTo("OBSERVATION_HELD");
		verifyNoInteractions(reconcile, commands);
	}
	@Test void source_conflict_is_durably_held() {
		proposal.noteProducerConflict("MENU_SOURCE_CONFLICT: NAVER / BAEMIN");
		assertThat(service.apply("current").held()).isEqualTo(1);
		assertThat(proposal.getStatus().name()).isEqualTo("PENDING");
		assertThat(jdbc.queryForObject("SELECT price FROM menu WHERE id=1", Integer.class)).isEqualTo(3000);
		assertThat(service.apply("current").held()).isEqualTo(1);
		verify(commands, times(1)).approveAutomatically(anyLong(), any(), any(), any());
	}
	@Test void interrupted_apply_is_unknown_and_never_retried() {
		when(commands.approveAutomatically(anyLong(), any(), any(), any())).thenAnswer(invocation -> {
			proposal.beginApply(UUID.randomUUID());
			throw new IllegalStateException("crash after claim");
		});
		assertThatThrownBy(() -> service.apply("current")).hasMessage("crash after claim");
		assertThat(service.apply("current").unknown()).isEqualTo(1);
		verify(commands, times(1)).approveAutomatically(anyLong(), any(), any(), any());
	}
	@Test void concurrent_same_store_does_not_apply_twice() throws Exception {
		var entered = new CountDownLatch(1);
		var release = new CountDownLatch(1);
		when(commands.approveAutomatically(anyLong(), any(), any(), any())).thenAnswer(invocation -> {
			entered.countDown();
			if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
			proposal.beginApply(UUID.randomUUID()); proposal.completeApply(null); return null;
		});
		var executor = Executors.newSingleThreadExecutor();
		try {
			var first = executor.submit(() -> service.apply("current"));
			assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
			assertThatThrownBy(() -> service.apply("current")).isInstanceOf(CannotAcquireLockException.class);
			release.countDown();
			assertThat(first.get(5, TimeUnit.SECONDS).applied()).isEqualTo(1);
		} finally { release.countDown(); executor.shutdownNow(); }
		verify(commands, times(1)).approveAutomatically(anyLong(), any(), any(), any());
	}
	@Test void strict_insert_never_overwrites_a_menu_or_a_remapped_store() {
		assertThat(serving.insertObservedMenu(1, "123", "소금빵", 9000, null, null).outcome().name()).isEqualTo("BLOCKED_EXISTING_MENU");
		assertThat(serving.insertObservedMenu(1, "wrong", "새빵", 4000, null, null).outcome().name()).isEqualTo("BLOCKED_EXISTING_MENU");
		assertThat(serving.insertObservedMenu(1, "123", "새빵", 4000, null, null).outcome().name()).isEqualTo("INSERTED");
		assertThat(jdbc.queryForObject("SELECT price FROM menu WHERE id=1", Integer.class)).isEqualTo(3000);
	}
	@Test void category_and_removal_compare_current_values_and_store_association() {
		jdbc.execute("INSERT INTO menu_category VALUES(1,'빵')");
		assertThat(serving.updateObservedMenuCategory(1, 1, "wrong", null, "빵")).isZero();
		assertThat(serving.updateObservedMenuCategory(1, 1, "123", null, "빵")).isEqualTo(1);
		assertThat(serving.updateObservedMenuCategory(1, 1, "123", null, "빵")).isZero();
		assertThat(serving.softDeleteObservedMenu(1, 1, "wrong", "소금빵", 3000, null)).isZero();
		assertThat(serving.softDeleteObservedMenu(1, 1, "123", "소금빵", 2000, null)).isZero();
		assertThat(serving.softDeleteObservedMenu(1, 1, "123", "소금빵", 3000, null)).isEqualTo(1);
		assertThat(serving.softDeleteObservedMenu(1, 1, "123", "소금빵", 3000, null)).isZero();
	}

	@Test void poke_policy_soft_deletes_once_and_preserves_row_and_history() {
		jdbc.execute("UPDATE menu SET name='베이글 연어 포케',price=11500,description='' WHERE id=1");
		jdbc.execute("CREATE TABLE menu_history(menu_id bigint REFERENCES menu(id))");
		jdbc.execute("INSERT INTO menu_history VALUES(1)");
		String old = "{\"name\":\"베이글 연어 포케\",\"price\":11500,\"description\":\"\",\"reason\":\"제외 필터\"}";
		proposal = ChangeProposal.builder().storeId(1L).target("menu.1.removed").oldValue(old).newValue(null)
			.source(ProvenanceSource.NAVER).producer(ProposalProducer.NAVER_SNAPSHOT_RECONCILE).build();
		ReflectionTestUtils.setField(proposal, "id", 7L);
		when(reconcile.reconcileAutomatic(eq(1L), any(), any(), anyBoolean(), anyBoolean()))
			.thenReturn(List.of(new AutomaticCandidate(7, "menu.1.removed", old, null, ProvenanceSource.NAVER)));
		when(commands.approveAutomatically(eq(7L), eq(serving), any(), any())).thenAnswer(invocation -> {
			ProposalCommandService.AutomaticApproval approval = invocation.getArgument(3);
			approval.verify().accept(proposal);
			assertThat(serving.softDeleteObservedMenu(1, 1, approval.externalId(), "베이글 연어 포케", 11500, "")).isEqualTo(1);
			proposal.beginApply(UUID.randomUUID()); proposal.completeApply(null);
			return null;
		});
		assertThat(service.apply("current").applied()).isEqualTo(1);
		assertThat(service.apply("current").alreadyApplied()).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM menu WHERE deleted_at IS NOT NULL", Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM menu_history", Integer.class)).isEqualTo(1);
	}

	@Test void changed_description_or_user_contribution_prevents_policy_exclusion() {
		jdbc.execute("UPDATE menu SET name='베이글 연어 포케',price=11500,description='사용자 설명' WHERE id=1");
		assertThat(serving.softDeleteObservedMenu(1, 1, "123", "베이글 연어 포케", 11500, "")).isZero();
		jdbc.execute("UPDATE menu SET created_by_member_id=42 WHERE id=1");
		assertThat(serving.softDeleteObservedMenu(1, 1, "123", "베이글 연어 포케", 11500, "사용자 설명")).isZero();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM menu WHERE deleted_at IS NULL", Integer.class)).isEqualTo(1);
	}
}
