package sweetgirl.dataops.sync.service;

import static org.assertj.core.api.Assertions.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import sweetgirl.dataops.sync.repository.StoreSyncStateRepository;

@EnabledIfEnvironmentVariable(named = "DATAOPS_AUTO_APPLY_TEST_JDBC_URL", matches = "jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/.*")
class SelectedDuePostgresTest {
	@Test void selection_keeps_due_lease_state_source_and_atomic_generation_guards() throws Exception {
		String base = System.getenv("DATAOPS_AUTO_APPLY_TEST_JDBC_URL");
		var admin = new JdbcTemplate(new DriverManagerDataSource(base, "postgres", ""));
		String schema = "selected_" + UUID.randomUUID().toString().replace("-", "");
		admin.execute("CREATE SCHEMA " + schema);
		var source = new DriverManagerDataSource(base + "?currentSchema=" + schema, "postgres", "");
		var jdbc = new JdbcTemplate(source);
		try {
			jdbc.execute("CREATE TABLE store_sync_state(id bigint primary key,store_id bigint,source text,state text,next_due_at timestamp,last_attempt_at timestamp,claimed_at timestamp,claim_token uuid,attempt_seq bigint default 0)");
			jdbc.execute("INSERT INTO store_sync_state(id,store_id,source,state,next_due_at) SELECT n,n,'NAVER','ACTIVE',localtimestamp-interval '1 day' FROM generate_series(1,7) n");
			jdbc.execute("UPDATE store_sync_state SET last_attempt_at=localtimestamp WHERE id=3");
			jdbc.execute("UPDATE store_sync_state SET state='PARKED' WHERE id=4");
			jdbc.execute("UPDATE store_sync_state SET next_due_at=localtimestamp+interval '1 day' WHERE id=5");
			jdbc.execute("UPDATE store_sync_state SET source='BAEMIN' WHERE id=6");
			String query = StoreSyncStateRepository.class.getMethod("claimSelectedDue", List.class, LocalDateTime.class,
				LocalDateTime.class, int.class, UUID.class).getAnnotation(Query.class).value();
			var now = LocalDateTime.now();
			var params = Map.of("storeIds", List.of(1L, 3L, 4L, 5L, 6L, 7L), "now", now,
				"leaseExpiry", now.minusHours(2), "limit", 50, "token", UUID.randomUUID());
			try (var held = source.getConnection()) {
				held.setAutoCommit(false);
				try (var statement = held.createStatement()) { statement.executeQuery("SELECT id FROM store_sync_state WHERE id=7 FOR UPDATE").close(); }
				assertThat(new NamedParameterJdbcTemplate(source).update(query, params)).isEqualTo(1);
				assertThat(new NamedParameterJdbcTemplate(source).update(query, params)).isZero();
				held.rollback();
			}
			assertThat(jdbc.queryForList("SELECT store_id FROM store_sync_state WHERE attempt_seq=1", Long.class)).containsExactly(1L);
			assertThat(new NamedParameterJdbcTemplate(source).update(query, params)).isEqualTo(1);
			assertThat(jdbc.queryForList("SELECT store_id FROM store_sync_state WHERE attempt_seq=1 ORDER BY store_id", Long.class)).containsExactly(1L, 7L);
		} finally { admin.execute("DROP SCHEMA " + schema + " CASCADE"); }
	}
}
