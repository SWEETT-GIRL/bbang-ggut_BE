package sweetgirl.dataops.snapshot.service;

import java.time.ZoneOffset;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import sweetgirl.dataops.snapshot.dto.response.SnapshotSubmitResponse;
import tools.jackson.databind.JsonNode;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import sweetgirl.dataops.snapshot.code.SnapshotErrorCode;
import sweetgirl.dataops.snapshot.concurrency.StoreObservationBarrier;
import sweetgirl.dataops.snapshot.dto.request.SnapshotSubmitRequest;
import sweetgirl.dataops.snapshot.exception.SnapshotException;
import tools.jackson.databind.ObjectMapper;

@Service
public class ResponseCaptureService {
	private final JdbcTemplate jdbc;
	private final ObjectMapper mapper;
	private final StoreObservationBarrier observationBarrier;
	public ResponseCaptureService(DataSource dataSource, ObjectMapper mapper, StoreObservationBarrier observationBarrier) {
		this.jdbc = new JdbcTemplate(dataSource);
		this.mapper = mapper;
		this.observationBarrier = observationBarrier;
	}

	/** Independent commit before any parser/evidence invocation. Never uses a serving datasource. */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public String receive(SnapshotSubmitRequest request) {
		observationBarrier.lockObservation(request.storeId());
		String id = request.observationId() == null ? UUID.randomUUID().toString() : request.observationId();
		if (id.isBlank() || id.length() > 64) throw new SnapshotException(SnapshotErrorCode.OBSERVATION_INVALID);
		String payload = ResponseDigests.canonical(request.payload(), mapper);
		String payloadDigest = ResponseDigests.sha256(payload);
		String metadata = mapper.writeValueAsString(Arrays.asList(request.storeId(), request.source(), request.externalId(),
			request.observedAt() == null ? null : request.observedAt().toInstant().toString(), request.attemptSeq(),
			Objects.toString(request.fetchedAt(), null), payloadDigest));
		String digest = ResponseDigests.sha256(metadata);
		jdbc.update("""
			INSERT INTO response_capture
			(capture_id, request_digest, store_id, source, external_id, observed_at, attempt_seq, payload, payload_digest)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
			""", id, digest, request.storeId(), request.source().name(), request.externalId(),
			request.observedAt() == null ? null : request.observedAt().withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS),
			request.attemptSeq(), payload, payloadDigest);
		String previous = jdbc.queryForObject("SELECT request_digest FROM response_capture WHERE capture_id = ?", String.class, id);
		if (!digest.equals(previous)) throw new SnapshotException(SnapshotErrorCode.OBSERVATION_CONFLICT);
		return id;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void assess(String captureId, String source, ResponseDiagnostics.Report report) {
		Long storeId = jdbc.queryForObject("SELECT store_id FROM response_capture WHERE capture_id = ?", Long.class, captureId);
		observationBarrier.lockObservation(storeId);
		String diagnostics = mapper.writeValueAsString(report.issues().stream().distinct().toList());
		jdbc.update("UPDATE response_capture SET status = ?, parser_digest = ?, contract_version = ?, diagnostics = ?, target_verified = ? WHERE capture_id = ?",
			report.assessment(), report.parserDigest(), ResponseDiagnostics.CONTRACT, diagnostics, report.targetVerified(), captureId);
		if (report.issues().isEmpty()) return;
		var signature = new TreeSet<String>();
		report.issues().forEach(issue -> signature.add(mapper.writeValueAsString(issue)));
		String fingerprint = ResponseDigests.sha256(mapper.writeValueAsString(List.of(source,
			ResponseDiagnostics.VERSION, ResponseDiagnostics.CONTRACT, report.parserDigest(), signature)));
		jdbc.update("""
			INSERT INTO response_incident (fingerprint, source, parser_digest, contract_version, diagnostics, model_eligible)
			VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
			""", fingerprint, source, report.parserDigest(), ResponseDiagnostics.CONTRACT, diagnostics, report.modelEligible());
		jdbc.update("INSERT INTO response_incident_sample (fingerprint, capture_id) VALUES (?, ?) ON CONFLICT DO NOTHING", fingerprint, captureId);
	}

	public Optional<SnapshotSubmitResponse> findProcessedResponse(String captureId) {
		return jdbc.query("SELECT processed_response FROM response_capture WHERE capture_id = ? AND processed_response IS NOT NULL",
			(rs, row) -> mapper.readValue(rs.getString(1), SnapshotSubmitResponse.class), captureId).stream().findFirst();
	}

	/** Must commit or roll back together with snapshots, sync state and proposals. */
	@Transactional(propagation = Propagation.MANDATORY)
	public void complete(String captureId, SnapshotSubmitResponse response) {
		jdbc.update("UPDATE response_capture SET processed_response = ?, processed_at = CURRENT_TIMESTAMP WHERE capture_id = ?",
			mapper.writeValueAsString(response), captureId);
	}

	public boolean isLatest(SnapshotSubmitRequest request) {
		if (request.observedAt() == null) return false;
		return latest(request.storeId(), request.source().name(), request.externalId())
			.map(c -> c.id().equals(request.observationId())).orElse(false);
	}

	/** A newer different raw, including HELD, prevents an old snapshot from being rejudged. */
	public boolean isCurrentSnapshot(long storeId, String source, String externalId,
			LocalDateTime fetchedAt, JsonNode payload) {
		// Both persisted observations and snapshots use microseconds. Comparing a DB-rounded
		// timestamp with the original nanoseconds can falsely make a tied capture look older.
		var snapshotInstant = fetchedAt.atZone(ZoneId.of("Asia/Seoul")).toInstant().truncatedTo(ChronoUnit.MICROS);
		return latest(storeId, source, externalId).map(c ->
			c.observedAt().toInstant().truncatedTo(ChronoUnit.MICROS).isBefore(snapshotInstant)
				|| c.payloadDigest().equals(ResponseDigests.sha256(ResponseDigests.canonical(payload, mapper))))
			.orElse(true);
	}

	private record Latest(String id, OffsetDateTime observedAt, String payloadDigest) {}
	private Optional<Latest> latest(long storeId, String source, String externalId) {
		return jdbc.query("""
			SELECT capture_id, observed_at, received_at, payload_digest FROM response_capture
			WHERE store_id = ? AND source = ? AND external_id = ? AND target_verified = TRUE
			ORDER BY observed_at DESC, received_at DESC, capture_id DESC
			""", (rs, row) -> new Object[] {rs.getString(1), rs.getObject(2, OffsetDateTime.class),
				rs.getObject(3, OffsetDateTime.class), rs.getString(4)}, storeId, source, externalId).stream()
			.filter(row -> row[1] != null && !((OffsetDateTime)row[1]).isBefore(OffsetDateTime.parse("1970-01-01T00:00:00Z"))
				&& !((OffsetDateTime)row[1]).isAfter(((OffsetDateTime)row[2]).plusMinutes(5)))
			.map(row -> new Latest((String)row[0], (OffsetDateTime)row[1], (String)row[3])).findFirst();
	}
}
