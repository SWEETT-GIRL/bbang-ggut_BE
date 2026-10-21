package sweetgirl.dataops.snapshot.service;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
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
import sweetgirl.dataops.sync.enums.SyncSource;
import tools.jackson.databind.ObjectMapper;

class ResponseCaptureServiceTest {
	private final ObjectMapper mapper = new ObjectMapper();
	private final OffsetDateTime observed = OffsetDateTime.now();
	private JdbcTemplate jdbc;
	private ResponseCaptureService service;
	private TransactionTemplate outer;
	private java.sql.Connection schemaConnection;
	@BeforeEach void setup() throws Exception {
		var ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
		jdbc = new JdbcTemplate(ds);
		// H2 2.4 binds constant-set CHECK evaluation to its creating session; retain that session.
		schemaConnection = ds.getConnection();
		try (var statement = schemaConnection.createStatement()) {
			statement.execute(Files.readString(Path.of("src/main/resources/db/dataops-migration/V20261013100000__response_capture_and_incident.sql")));
			statement.execute("CREATE TABLE store_sync_state (content_hash VARCHAR(64))");
			statement.execute(Files.readString(Path.of("src/main/resources/db/dataops-migration/V20261013233000__response_menu_scope.sql")));
		}
		var manager = new DataSourceTransactionManager(ds);
		var proxy = new ProxyFactory(new ResponseCaptureService(ds, mapper, org.mockito.Mockito.mock(sweetgirl.dataops.snapshot.concurrency.StoreObservationBarrier.class)));
		proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
		service = (ResponseCaptureService)proxy.getProxy();
		outer = new TransactionTemplate(manager);
	}
	@AfterEach void closeSchemaConnection() throws Exception { schemaConnection.close(); }
	SnapshotSubmitRequest request(String id, String payload) {
		return new SnapshotSubmitRequest(1L, SyncSource.NAVER, mapper.readTree(payload), null, id, "123", observed, 1L);
	}
	@Test void raw_survives_downstream_rollback() {
		assertThatThrownBy(() -> outer.execute(status -> {
			service.receive(request("durable", "{\"a\":1}"));
			throw new IllegalStateException("parser/reconcile failed");
		})).isInstanceOf(IllegalStateException.class);
		assertThat(jdbc.queryForObject("SELECT payload FROM response_capture WHERE capture_id='durable'", String.class)).isEqualTo("{\"a\":1}");
	}
	@Test void same_request_retries_are_idempotent_but_content_conflicts_are_rejected() {
		service.receive(request("same", "{\"a\":1,\"b\":2}"));
		service.receive(request("same", "{\"b\":2,\"a\":1}"));
		assertThatThrownBy(() -> service.receive(request("same", "{\"a\":3}"))).isInstanceOf(SnapshotException.class);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM response_capture", Integer.class)).isEqualTo(1);
	}
	@Test void incidents_group_across_observations_and_sample_retries_do_not_duplicate() {
		var report = new ResponseDiagnostics.Report("parser", List.of(new ResponseDiagnostics.Issue("menus.price", "TYPE", "OBJECT", true)));
		for (String id : List.of("first", "second", "second")) {
			service.receive(request(id, "{}"));
			service.assess(id, "NAVER", report);
		}
		assertThat(jdbc.queryForObject("SELECT count(*) FROM response_incident", Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM response_incident_sample", Integer.class)).isEqualTo(2);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM response_capture WHERE status='HELD'", Integer.class)).isEqualTo(2);
	}

	@Test void partial_assessment_is_durable_but_receipt_rolls_back_with_processing() {
		service.receive(request("partial", "{}"));
		var report = new ResponseDiagnostics.Report("parser", List.of(new ResponseDiagnostics.Issue("menus", "MENU_INCOMPLETE", "NULL", false)));
		assertThatThrownBy(() -> outer.execute(status -> {
			service.assess("partial", "NAVER", report);
			service.complete("partial", new sweetgirl.dataops.snapshot.dto.response.SnapshotSubmitResponse(null, false, null, "partial", "PARTIAL", List.of("MENU_INCOMPLETE")));
			throw new IllegalStateException("downstream failure");
		})).isInstanceOf(IllegalStateException.class);
		assertThat(jdbc.queryForObject("SELECT status FROM response_capture WHERE capture_id='partial'", String.class)).isEqualTo("PARTIAL");
		assertThat(service.findProcessedResponse("partial")).isEmpty();
		outer.executeWithoutResult(status -> service.complete("partial", new sweetgirl.dataops.snapshot.dto.response.SnapshotSubmitResponse(7L, true, "hash", "partial", "PARTIAL", List.of("MENU_INCOMPLETE"))));
		assertThat(service.findProcessedResponse("partial").orElseThrow().snapshotId()).isEqualTo(7L);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM response_incident", Integer.class)).isEqualTo(1);
	}

	@Test void newer_held_capture_blocks_replay_but_unverified_target_and_future_clock_do_not() {
		var ready = new ResponseDiagnostics.Report("parser", List.of());
		service.receive(request("old", "{\"v\":1}"));
		service.assess("old", "NAVER", ready);
		var newer = new SnapshotSubmitRequest(1L, SyncSource.NAVER, mapper.readTree("{\"v\":2}"), null, "new", "123", observed.plusSeconds(1), 2L);
		service.receive(newer);
		service.assess("new", "NAVER", new ResponseDiagnostics.Report("parser", List.of(new ResponseDiagnostics.Issue("price", "PRICE_SHAPE_UNSUPPORTED", "OBJECT", true))));
		assertThat(service.isLatest(request("old", "{\"v\":1}"))).isFalse();
		assertThat(service.isCurrentSnapshot(1L, "NAVER", "123", observed.atZoneSameInstant(java.time.ZoneId.of("Asia/Seoul")).toLocalDateTime(), mapper.readTree("{\"v\":1}"))).isFalse();
		var future = new SnapshotSubmitRequest(1L, SyncSource.NAVER, mapper.readTree("{}"), null, "future", "123", observed.plusDays(1), 3L);
		service.receive(future);
		service.assess("future", "NAVER", ready);
		var wrong = new SnapshotSubmitRequest(1L, SyncSource.NAVER, mapper.readTree("{}"), null, "wrong", "123", observed.plusSeconds(2), 3L);
		service.receive(wrong);
		service.assess("wrong", "NAVER", new ResponseDiagnostics.Report("parser", List.of(new ResponseDiagnostics.Issue("id", "IDENTITY_UNKNOWN", "mismatch", false))));
		assertThat(service.isLatest(newer)).isTrue();
	}
	@Test void same_observed_clock_with_newer_received_different_raw_blocks_snapshot_replay() {
		service.receive(request("a", "{\"value\":1}"));
		service.assess("a", "NAVER", new ResponseDiagnostics.Report("parser", List.of()));
		service.receive(request("b", "{\"value\":2}"));
		service.assess("b", "NAVER", new ResponseDiagnostics.Report("parser", List.of()));
		jdbc.update("UPDATE response_capture SET received_at = ? WHERE capture_id = 'b'", observed.plusSeconds(1));
		var at = observed.atZoneSameInstant(java.time.ZoneId.of("Asia/Seoul")).toLocalDateTime();
		assertThat(service.isCurrentSnapshot(1L, "NAVER", "123", at, mapper.readTree("{\"value\":1}"))).isFalse();
		assertThat(service.isCurrentSnapshot(1L, "NAVER", "123", at, mapper.readTree("{\"value\":2}"))).isTrue();
	}

	@Test void capture_and_snapshot_use_the_same_microsecond_precision_for_both_rounding_directions() {
		for (int nanos : List.of(123_456_100, 123_456_900, 999_999_900)) {
			var at = OffsetDateTime.parse("2026-10-13T01:02:03Z").withNano(nanos);
			String externalId = "place-" + nanos;
			var request = new SnapshotSubmitRequest(1L, SyncSource.NAVER, mapper.readTree("{\"value\":2}"),
				null, "precision-" + nanos, externalId, at, 1L);
			service.receive(request);
			service.assess(request.observationId(), "NAVER", new ResponseDiagnostics.Report("parser", List.of()));
			var stored = jdbc.queryForObject("SELECT observed_at FROM response_capture WHERE capture_id = ?",
				OffsetDateTime.class, request.observationId());
			assertThat(stored.toInstant()).isEqualTo(at.toInstant().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
			var fetched = at.atZoneSameInstant(java.time.ZoneId.of("Asia/Seoul")).toLocalDateTime();
			assertThat(service.isCurrentSnapshot(1L, "NAVER", externalId, fetched, mapper.readTree("{\"value\":1}"))).isFalse();
			assertThat(service.isCurrentSnapshot(1L, "NAVER", externalId, fetched, mapper.readTree("{\"value\":2}"))).isTrue();
			// A genuine one-microsecond older capture still permits a later snapshot.
			assertThat(service.isCurrentSnapshot(1L, "NAVER", externalId, fetched.plusNanos(1_000), mapper.readTree("{\"value\":1}"))).isTrue();
		}
	}

	@Test void legacy_database_rounded_capture_does_not_bypass_tied_snapshot_guard() {
		var at = OffsetDateTime.parse("2026-10-13T01:02:03.123456100Z");
		service.receive(new SnapshotSubmitRequest(1L, SyncSource.NAVER, mapper.readTree("{\"value\":2}"),
			null, "legacy-rounded", "123", at, 1L));
		service.assess("legacy-rounded", "NAVER", new ResponseDiagnostics.Report("parser", List.of()));
		// Emulate rows written before explicit truncation: the DB rounded nanoseconds down.
		jdbc.update("UPDATE response_capture SET observed_at = ? WHERE capture_id = 'legacy-rounded'", at);
		assertThat(service.isCurrentSnapshot(1L, "NAVER", "123",
			at.atZoneSameInstant(java.time.ZoneId.of("Asia/Seoul")).toLocalDateTime(), mapper.readTree("{\"value\":1}"))).isFalse();
	}

}
