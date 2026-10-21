package sweetgirl.dataops.snapshot.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.dataops.snapshot.code.SnapshotErrorCode;
import sweetgirl.dataops.snapshot.dto.request.SnapshotSubmitRequest;
import sweetgirl.dataops.snapshot.exception.SnapshotException;
import sweetgirl.dataops.snapshot.parser.MenuEvidenceParser;
import sweetgirl.dataops.snapshot.parser.MenuEvidenceParser.Evidence;
import sweetgirl.dataops.snapshot.repository.MenuEvidenceRepository;
import sweetgirl.dataops.snapshot.repository.MenuEvidenceRepository.Observation;
import sweetgirl.dataops.sync.enums.SyncSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class MenuEvidenceService {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	private final MenuEvidenceRepository repository;
	private final MenuEvidenceParser parser;
	private final ObjectMapper objectMapper;

	/** A failed reconcile/snapshot transaction must not erase a completed fetch. */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public Recorded record(SnapshotSubmitRequest request) {
		String expectedExternalId = repository.findExternalId(request.storeId(), request.source().name())
			.orElseThrow(() -> new SnapshotException(SnapshotErrorCode.SNAPSHOT_SYNC_STATE_NOT_FOUND));
		LocalDateTime receivedAt = LocalDateTime.now(KST).truncatedTo(ChronoUnit.MICROS);
		boolean legacy = request.observationId() == null && request.externalId() == null && request.observedAt() == null;
		if (!legacy && (request.observationId() == null || request.observationId().isBlank()
			|| request.observationId().length() > 64 || request.externalId() == null || request.externalId().isBlank()
			|| request.observedAt() == null)) {
			throw new SnapshotException(SnapshotErrorCode.OBSERVATION_INVALID);
		}
		LocalDateTime observedAt = legacy ? receivedAt
			: request.observedAt().atZoneSameInstant(KST).toLocalDateTime().truncatedTo(ChronoUnit.MICROS);
		// A future client clock must not pin latest evidence indefinitely. Historical late observations remain replayable.
		if (!legacy && (observedAt.isAfter(receivedAt.plusMinutes(5)) || observedAt.isBefore(LocalDateTime.of(1970, 1, 1, 0, 0)))) {
			throw new SnapshotException(SnapshotErrorCode.OBSERVATION_INVALID);
		}
		Evidence evidence = !legacy && request.source() == SyncSource.NAVER
			&& expectedExternalId.equals(request.externalId())
				? parser.parse(request.payload(), request.externalId()) : Evidence.unknown();
		Observation observation = new Observation(legacy ? UUID.randomUUID().toString() : request.observationId(),
			request.storeId(), request.source().name(), request.externalId(), observedAt, receivedAt,
			payloadHash(request.payload()), parser.version());
		boolean inserted = repository.insert(observation, objectMapper.writeValueAsString(evidence.menuNames()),
			objectMapper.writeValueAsString(evidence.representativeNames()), evidence.menuComplete(), evidence.identityVerified());
		if (!inserted) {
			Observation existing = repository.find(observation.observationId()).orElseThrow();
			if (!sameRequest(existing, observation)) {
				throw new SnapshotException(SnapshotErrorCode.OBSERVATION_CONFLICT);
			}
		}
		return new Recorded(observation.observationId(), observedAt, !inserted, legacy,
			legacy || evidence.identityVerified());
	}

	public boolean isLatest(Recorded recorded, SnapshotSubmitRequest request) {
		return recorded.legacy() || repository.isLatestVerified(recorded.observationId(), request.storeId(),
			request.source().name(), request.externalId());
	}

	private boolean sameRequest(Observation left, Observation right) {
		return left.storeId() == right.storeId() && left.source().equals(right.source())
			&& Objects.equals(left.externalId(), right.externalId()) && left.observedAt().equals(right.observedAt())
			&& left.payloadHash().equals(right.payloadHash());
	}

	private String payloadHash(JsonNode payload) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(objectMapper.writeValueAsString(canonical(payload)).getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new SnapshotException(SnapshotErrorCode.SNAPSHOT_HASH_FAILED);
		}
	}

	private Object canonical(JsonNode node) {
		if (node.isObject()) {
			Map<String, Object> sorted = new TreeMap<>();
			node.properties().forEach(entry -> sorted.put(entry.getKey(), canonical(entry.getValue())));
			return sorted;
		}
		if (node.isArray()) {
			List<Object> values = new ArrayList<>();
			node.forEach(value -> values.add(canonical(value)));
			return values;
		}
		return node;
	}

	public record Recorded(String observationId, LocalDateTime observedAt, boolean duplicate, boolean legacy,
			boolean targetVerified) {}
}
