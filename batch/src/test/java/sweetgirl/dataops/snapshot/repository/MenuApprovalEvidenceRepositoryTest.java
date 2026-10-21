package sweetgirl.dataops.snapshot.repository;

import static org.assertj.core.api.Assertions.*;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class MenuApprovalEvidenceRepositoryTest {
	private JdbcTemplate jdbc;
	private MenuEvidenceRepository repository;

	@BeforeEach
	void setup() {
		var dataSource = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
		jdbc = new JdbcTemplate(dataSource);
		repository = new MenuEvidenceRepository(dataSource);
		jdbc.execute("""
			CREATE TABLE response_capture (capture_id VARCHAR PRIMARY KEY, store_id BIGINT, source VARCHAR,
			 external_id VARCHAR, payload VARCHAR, status VARCHAR, observed_at TIMESTAMP WITH TIME ZONE,
			 received_at TIMESTAMP WITH TIME ZONE, payload_digest VARCHAR, target_verified BOOLEAN NOT NULL)
			""");
		jdbc.execute("""
			CREATE TABLE menu_evidence_observation (observation_id VARCHAR, store_id BIGINT, source VARCHAR,
			 external_id VARCHAR, payload_hash VARCHAR, menu_complete BOOLEAN, identity_verified BOOLEAN)
			""");
	}

	@Test
	void newer_held_capture_wins_even_without_menu_evidence_but_late_older_replay_does_not() {
		capture("complete", "123", "READY", "2026-10-13T10:00:00Z", "2026-10-13T10:01:00Z");
		jdbc.update("INSERT INTO menu_evidence_observation VALUES ('complete',1,'NAVER','123','hash',true,true)");
		capture("held", "123", "HELD", "2026-10-13T11:00:00Z", "2026-10-13T11:01:00Z");
		capture("replay", "123", "READY", "2026-10-13T09:00:00Z", "2026-10-13T12:01:00Z");
		var latest = repository.latestCaptureForMenuApproval(1, "NAVER", "123").orElseThrow();
		assertThat(latest.captureId()).isEqualTo("held");
		assertThat(latest.menuComplete()).isFalse();
		assertThat(latest.identityVerified()).isFalse();
		assertThat(latest.observedAt()).isEqualTo(LocalDateTime.of(2026, 10, 13, 20, 0));
	}

	@Test
	void old_link_is_excluded_but_same_target_received_input_holds_until_assessed() {
		capture("old-link", "999", "HELD", "2026-10-13T14:00:00Z", "2026-10-13T14:01:00Z");
		capture("unknown-clock", "123", "RECEIVED", null, "2026-10-13T11:00:00Z");
		jdbc.update("UPDATE response_capture SET target_verified = FALSE WHERE capture_id='unknown-clock'");
		assertThat(repository.latestCaptureForMenuApproval(1, "NAVER", "123").orElseThrow().captureId())
			.isEqualTo("unknown-clock");
		capture("latest", "123", "READY", "2026-10-13T12:00:00Z", "2026-10-13T12:01:00Z");
		assertThat(repository.latestCaptureForMenuApproval(1, "NAVER", "123").orElseThrow().captureId())
			.isEqualTo("latest");
	}

	@Test
	void evidence_from_another_store_cannot_authenticate_a_capture() {
		capture("complete", "123", "READY", "2026-10-13T10:00:00Z", "2026-10-13T10:01:00Z");
		jdbc.update("INSERT INTO menu_evidence_observation VALUES ('complete',2,'NAVER','123','hash',true,true)");
		assertThat(repository.latestCaptureForMenuApproval(1, "NAVER", "123").orElseThrow().identityVerified()).isFalse();
	}

	@Test
	void invalid_future_prehistory_and_assessed_identity_do_not_pin_latest_observation() {
		capture("valid", "123", "READY", "2026-10-13T10:00:00Z", "2026-10-13T10:01:00Z");
		capture("future", "123", "HELD", "2099-01-01T00:00:00Z", "2026-10-13T11:00:00Z");
		capture("prehistory", "123", "HELD", "1969-12-31T23:59:59Z", "2026-10-13T11:00:00Z");
		capture("wrong-target", "123", "HELD", "2026-10-13T12:00:00Z", "2026-10-13T12:01:00Z");
		jdbc.update("UPDATE response_capture SET target_verified = FALSE WHERE capture_id='wrong-target'");
		assertThat(repository.latestCaptureForMenuApproval(1, "NAVER", "123").orElseThrow().captureId())
			.isEqualTo("valid");
		jdbc.update("DELETE FROM response_capture WHERE capture_id='valid'");
		assertThat(repository.latestCaptureForMenuApproval(1, "NAVER", "123")).isEmpty();
	}

	@Test
	void valid_partial_and_unassessed_same_target_received_remain_barriers() {
		capture("valid", "123", "READY", "2026-10-13T10:00:00Z", "2026-10-13T10:01:00Z");
		capture("partial", "123", "PARTIAL", "2026-10-13T11:00:00Z", "2026-10-13T11:01:00Z");
		assertThat(repository.latestCaptureForMenuApproval(1, "NAVER", "123").orElseThrow().captureId())
			.isEqualTo("partial");
		capture("received", "123", "RECEIVED", "2026-10-13T12:00:00Z", "2026-10-13T12:01:00Z");
		jdbc.update("UPDATE response_capture SET target_verified = FALSE WHERE capture_id='received'");
		assertThat(repository.latestCaptureForMenuApproval(1, "NAVER", "123").orElseThrow().captureId())
			.isEqualTo("received");
		jdbc.update("UPDATE response_capture SET status = 'HELD' WHERE capture_id='received'");
		assertThat(repository.latestCaptureForMenuApproval(1, "NAVER", "123").orElseThrow().captureId())
			.isEqualTo("partial");
	}

	private void capture(String id, String externalId, String status, String observedAt, String receivedAt) {
		jdbc.update("INSERT INTO response_capture VALUES (?,1,'NAVER',?,'{}',?,?,?,'hash',true)", id, externalId, status,
			observedAt == null ? null : OffsetDateTime.parse(observedAt), OffsetDateTime.parse(receivedAt));
	}
}
