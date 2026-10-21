package sweetgirl.dataops.snapshot.service;

import static org.assertj.core.api.Assertions.*;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import sweetgirl.dataops.snapshot.dto.request.SnapshotSubmitRequest;
import sweetgirl.dataops.snapshot.exception.SnapshotException;
import sweetgirl.dataops.snapshot.parser.MenuEvidenceParser;
import sweetgirl.dataops.snapshot.repository.MenuEvidenceRepository;
import sweetgirl.dataops.sync.enums.SyncSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** No Boot context, configured database, network, or production dependency: isolated H2 only. */
class MenuEvidenceServiceTest {

	private final ObjectMapper mapper = new ObjectMapper();
	private final OffsetDateTime observedAt = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1).truncatedTo(ChronoUnit.MICROS);
	private JdbcTemplate jdbc;
	private MenuEvidenceService service;
	private TransactionTemplate outerTransaction;

	@BeforeEach
	void setUp() {
		var dataSource = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
		jdbc = new JdbcTemplate(dataSource);
		jdbc.execute("CREATE TABLE store_sync_state (store_id BIGINT, source VARCHAR(20), external_id TEXT)");
		jdbc.update("INSERT INTO store_sync_state VALUES (1, 'NAVER', '123')");
		jdbc.execute("""
			CREATE TABLE menu_evidence_observation (
			 id BIGSERIAL PRIMARY KEY, observation_id VARCHAR(64) NOT NULL UNIQUE,
			 store_id BIGINT NOT NULL, source VARCHAR(20) NOT NULL, external_id TEXT,
			 observed_at TIMESTAMP NOT NULL, received_at TIMESTAMP NOT NULL, payload_hash CHAR(64) NOT NULL,
			 menu_names JSONB NOT NULL, representative_names JSONB NOT NULL,
			 menu_complete BOOLEAN NOT NULL, identity_verified BOOLEAN NOT NULL, parser_version TEXT NOT NULL)
			""");
		var manager = new DataSourceTransactionManager(dataSource);
		var target = new MenuEvidenceService(new MenuEvidenceRepository(dataSource), new MenuEvidenceParser(mapper), mapper);
		var proxy = new ProxyFactory(target);
		proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
		service = (MenuEvidenceService)proxy.getProxy();
		outerTransaction = new TransactionTemplate(manager);
	}

	private JsonNode payload() {
		var payload = mapper.createObjectNode();
		var root = mapper.createObjectNode();
		root.set("placeDetail({\"input\":{\"id\":\"123\"}})", mapper.readTree("{\"placeMenus\":{\"items\":[],\"total\":0}}"));
		payload.set("ROOT_QUERY", root);
		return payload;
	}

	private SnapshotSubmitRequest request(String id, OffsetDateTime when) {
		return new SnapshotSubmitRequest(1L, SyncSource.NAVER, payload(), null, id, "123", when, null);
	}

	@Test
	void unchanged_payload_on_two_fetches_creates_two_observations() {
		service.record(request("first", observedAt));
		service.record(request("second", observedAt.plusSeconds(1)));

		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM menu_evidence_observation", Integer.class)).isEqualTo(2);
		assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT payload_hash) FROM menu_evidence_observation", Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM menu_evidence_observation WHERE menu_complete AND identity_verified", Integer.class)).isEqualTo(2);
	}

	@Test
	void retry_is_idempotent_and_different_payload_or_target_metadata_conflicts() {
		SnapshotSubmitRequest request = request("same", observedAt);
		assertThat(service.record(request).duplicate()).isFalse();
		assertThat(service.record(request).duplicate()).isTrue();
		assertThatThrownBy(() -> service.record(new SnapshotSubmitRequest(1L, SyncSource.NAVER, mapper.readTree("{}"),
			null, "same", "123", observedAt, null))).isInstanceOf(SnapshotException.class);
		assertThatThrownBy(() -> service.record(request("same", observedAt.plusSeconds(1)))).isInstanceOf(SnapshotException.class);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM menu_evidence_observation", Integer.class)).isEqualTo(1);
	}

	@Test
	void evidence_survives_outer_transaction_failure() {
		assertThatThrownBy(() -> outerTransaction.execute(status -> {
			service.record(request("retained", observedAt));
			throw new IllegalStateException("reconcile failed after the fetch was recorded");
		})).isInstanceOf(IllegalStateException.class);

		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM menu_evidence_observation", Integer.class)).isEqualTo(1);
	}

	@Test
	void wrong_external_id_and_legacy_requests_are_never_absence_evidence() {
		var wrong = service.record(new SnapshotSubmitRequest(1L, SyncSource.NAVER, payload(), null, "wrong", "999", observedAt, null));
		var legacy = service.record(new SnapshotSubmitRequest(1L, SyncSource.NAVER, payload(), null));

		assertThat(wrong.targetVerified()).isFalse();
		assertThat(legacy.legacy()).isTrue();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM menu_evidence_observation", Integer.class)).isEqualTo(2);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM menu_evidence_observation WHERE menu_complete OR identity_verified", Integer.class)).isZero();
	}

	@Test
	void late_fetch_is_replayable_but_not_latest_and_offset_is_normalized_to_kst() {
		var newerRequest = request("newer", observedAt.plusSeconds(10));
		var newer = service.record(newerRequest);
		var olderRequest = request("older", observedAt);
		var older = service.record(olderRequest);

		assertThat(service.isLatest(older, olderRequest)).isFalse();
		assertThat(service.isLatest(newer, newerRequest)).isTrue();
		assertThat(older.observedAt()).isEqualTo(observedAt.withOffsetSameInstant(ZoneOffset.ofHours(9)).toLocalDateTime());
	}

	@Test
	void partial_metadata_and_far_future_clock_are_rejected_without_poisoning_latest() {
		assertThatThrownBy(() -> service.record(new SnapshotSubmitRequest(1L, SyncSource.NAVER, payload(), null,
			"partial", null, observedAt, null))).isInstanceOf(SnapshotException.class);
		assertThatThrownBy(() -> service.record(request("future", OffsetDateTime.now().plusHours(1))))
			.isInstanceOf(SnapshotException.class);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM menu_evidence_observation", Integer.class)).isZero();
	}

	@Test
	void concurrent_retries_insert_exactly_one_observation() throws Exception {
		var executor = Executors.newFixedThreadPool(2);
		var start = new CountDownLatch(1);
		try {
			var first = executor.submit(() -> { start.await(); return service.record(request("concurrent", observedAt)); });
			var second = executor.submit(() -> { start.await(); return service.record(request("concurrent", observedAt)); });
			start.countDown();
			assertThat(first.get(5, TimeUnit.SECONDS).duplicate()).isNotEqualTo(second.get(5, TimeUnit.SECONDS).duplicate());
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM menu_evidence_observation", Integer.class)).isEqualTo(1);
		} finally {
			executor.shutdownNow();
		}
	}
}
