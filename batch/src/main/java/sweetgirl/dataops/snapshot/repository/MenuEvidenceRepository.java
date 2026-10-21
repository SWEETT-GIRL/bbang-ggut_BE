package sweetgirl.dataops.snapshot.repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** DataOps datasource only: never use the optional serving/prod JdbcTemplate for evidence. */
@Repository
public class MenuEvidenceRepository {

	private final JdbcTemplate jdbc;

	public MenuEvidenceRepository(DataSource dataSource) {
		this.jdbc = new JdbcTemplate(dataSource);
	}

	public Optional<String> findExternalId(long storeId, String source) {
		List<String> rows = jdbc.query("SELECT external_id FROM store_sync_state WHERE store_id = ? AND source = ?",
			(rs, row) -> rs.getString(1), storeId, source);
		// A null external ID still means the sync state exists (legacy submissions remain supported).
		return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.get(0) == null ? "" : rows.get(0));
	}

	public boolean insert(Observation observation, String menuNames, String representativeNames,
			boolean menuComplete, boolean identityVerified) {
		return jdbc.update("""
			INSERT INTO menu_evidence_observation
			(observation_id, store_id, source, external_id, observed_at, received_at, payload_hash,
			 menu_names, representative_names, menu_complete, identity_verified, parser_version)
			VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?, ?, ?)
			ON CONFLICT DO NOTHING
			""", observation.observationId(), observation.storeId(), observation.source(), observation.externalId(),
			Timestamp.valueOf(observation.observedAt()), Timestamp.valueOf(observation.receivedAt()),
			observation.payloadHash(), menuNames, representativeNames, menuComplete, identityVerified,
			observation.parserVersion()) == 1;
	}

	public Optional<Observation> find(String observationId) {
		return jdbc.query("""
			SELECT observation_id, store_id, source, external_id, observed_at, received_at, payload_hash, parser_version
			FROM menu_evidence_observation WHERE observation_id = ?
			""", (rs, row) -> new Observation(rs.getString("observation_id"), rs.getLong("store_id"),
				rs.getString("source"), rs.getString("external_id"), rs.getTimestamp("observed_at").toLocalDateTime(),
				rs.getTimestamp("received_at").toLocalDateTime(), rs.getString("payload_hash"),
				rs.getString("parser_version")), observationId).stream().findFirst();
	}

	public boolean isLatestVerified(String observationId, long storeId, String source, String externalId) {
		List<String> latest = jdbc.query("""
			SELECT observation_id FROM menu_evidence_observation
			WHERE store_id = ? AND source = ? AND external_id = ? AND identity_verified
			ORDER BY observed_at DESC, id DESC LIMIT 1
			""", (rs, row) -> rs.getString(1), storeId, source, externalId);
		return latest.size() == 1 && observationId.equals(latest.get(0));
	}

	/**
	 * A valid same-target HELD/PARTIAL or not-yet-assessed RECEIVED capture blocks older absence evidence.
	 * Invalid clocks and assessed identity failures cannot pin that barrier indefinitely.
	 */
	public Optional<MenuApprovalCapture> latestCaptureForMenuApproval(long storeId, String source, String externalId) {
		return jdbc.query("""
			SELECT c.capture_id, c.payload, c.status, c.observed_at,
			       c.payload_digest, e.payload_hash, e.menu_complete, e.identity_verified
			FROM response_capture c LEFT JOIN menu_evidence_observation e
			  ON e.observation_id = c.capture_id AND e.store_id = c.store_id
			 AND e.source = c.source AND e.external_id = c.external_id
			WHERE c.store_id = ? AND c.source = ? AND c.external_id = ?
			  AND (c.target_verified = TRUE OR c.status = 'RECEIVED')
			  AND ((c.observed_at IS NULL AND c.status = 'RECEIVED')
			       OR (c.observed_at >= ? AND c.observed_at <= c.received_at + INTERVAL '5' MINUTE))
			ORDER BY COALESCE(c.observed_at, c.received_at) DESC, c.received_at DESC, c.capture_id DESC LIMIT 1
			""", (rs, row) -> new MenuApprovalCapture(rs.getString("capture_id"), rs.getString("payload"),
				rs.getString("status"), rs.getTimestamp("observed_at") == null ? null
					: rs.getTimestamp("observed_at").toInstant().atZone(java.time.ZoneId.of("Asia/Seoul")).toLocalDateTime(),
				rs.getString("payload_digest"), rs.getString("payload_hash"),
				rs.getBoolean("menu_complete"), rs.getBoolean("identity_verified")), storeId, source, externalId,
			java.time.OffsetDateTime.parse("1970-01-01T00:00:00Z"))
			.stream().findFirst();
	}

	public record MenuApprovalCapture(String captureId, String payload, String status, LocalDateTime observedAt,
			String payloadDigest, String evidencePayloadHash, boolean menuComplete, boolean identityVerified) {}

	public record Observation(String observationId, long storeId, String source, String externalId,
			LocalDateTime observedAt, LocalDateTime receivedAt, String payloadHash, String parserVersion) {}
}
