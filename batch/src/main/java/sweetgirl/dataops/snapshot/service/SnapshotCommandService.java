package sweetgirl.dataops.snapshot.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.dataops.reconcile.service.ReconcileService;
import sweetgirl.dataops.signal.service.StoreSignalService;
import sweetgirl.dataops.snapshot.code.SnapshotErrorCode;
import sweetgirl.dataops.snapshot.dto.request.SnapshotSubmitRequest;
import sweetgirl.dataops.snapshot.dto.response.SnapshotSubmitResponse;
import sweetgirl.dataops.snapshot.entity.PlaceSnapshot;
import sweetgirl.dataops.snapshot.exception.SnapshotException;
import sweetgirl.dataops.snapshot.parser.NaverPlaceParser;
import sweetgirl.dataops.snapshot.parser.ParsedPlace;
import sweetgirl.dataops.snapshot.parser.StoreSignalParser;
import sweetgirl.dataops.snapshot.repository.PlaceSnapshotRepository;
import sweetgirl.dataops.sync.entity.StoreSyncState;
import sweetgirl.dataops.sync.enums.SyncSource;
import sweetgirl.dataops.sync.repository.StoreSyncStateRepository;
import tools.jackson.databind.ObjectMapper;

/**
 * 워커 원본 제출 수신 — 정규화(canonical) 해시로 직전과 비교해 동일하면 insert 를 생략한다 (방문 카운터만).
 * 원본(payload)은 그대로 저장하되, 변경 판정은 의미 필드만 본다 (ContentHashService).
 * 영업시간은 해시 밖에서 요일별 정기 값 기억과 비교한다 (RegularHoursTracker, 해시 3세대부터).
 * 방문 근거는 별도 트랜잭션으로 항상 기록하며, 같은 관측 ID 재전송은 방문으로 중복 계산하지 않는다.
 * 변경 시 저장 후 판정을 트리거한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class SnapshotCommandService {

	private final PlaceSnapshotRepository placeSnapshotRepository;
	private final StoreSyncStateRepository storeSyncStateRepository;
	private final ContentHashService contentHashService;
	private final NaverPlaceParser naverPlaceParser;
	private final ReconcileService reconcileService;
	private final ObjectMapper objectMapper;
	private final PlatformTransactionManager transactionManager;
	private final MenuEvidenceService menuEvidenceService;
	private final StoreSignalService storeSignalService;
	private final StoreSignalParser storeSignalParser;
	private final RegularHoursTracker regularHoursTracker;
	private final ResponseCaptureService responseCaptureService;
	private final ResponseDiagnostics responseDiagnostics;

	public SnapshotSubmitResponse submit(SnapshotSubmitRequest request) {
		String captureId = responseCaptureService.receive(request);
		var completed = responseCaptureService.findProcessedResponse(captureId);
		if (completed.isPresent()) return completed.get();
		ResponseDiagnostics.Report diagnostic = responseDiagnostics.inspect(request);
		responseCaptureService.assess(captureId, request.source().name(), diagnostic);
		if (diagnostic.held()) {
			return new SnapshotSubmitResponse(null, false, null, captureId, "HELD",
				diagnostic.issues().stream().map(ResponseDiagnostics.Issue::code).distinct().toList(), diagnostic.menuSources());
		}
		SnapshotSubmitResponse processed = processValidated(request, diagnostic, captureId);
		var response = new SnapshotSubmitResponse(processed.snapshotId(), processed.changed(), processed.contentHash(),
			captureId, "HELD".equals(processed.assessment()) ? "HELD" : diagnostic.assessment(),
			java.util.stream.Stream.concat(processed.reasons().stream(), diagnostic.issues().stream()
				.map(ResponseDiagnostics.Issue::code)).distinct().toList(),
			"HELD".equals(processed.assessment()) ? sweetgirl.dataops.snapshot.dto.response.MenuSourceAvailability.unverified() : diagnostic.menuSources());
		responseCaptureService.complete(captureId, response);
		return response;
	}

	private SnapshotSubmitResponse processValidated(SnapshotSubmitRequest request, ResponseDiagnostics.Report diagnostic, String captureId) {
		boolean menuAllowed = diagnostic.menuAllowed();
		boolean baeminAllowed = diagnostic.baeminAllowed();
		// This separate REQUIRES_NEW service commits even if parsing/reconcile below rolls back.
		MenuEvidenceService.Recorded observation = menuEvidenceService.record(request);
		StoreSyncState syncState = storeSyncStateRepository
			.findForSnapshotUpdate(request.storeId(), request.source())
			.orElseThrow(() -> new SnapshotException(SnapshotErrorCode.SNAPSHOT_SYNC_STATE_NOT_FOUND));
		if (!observation.targetVerified() || (!observation.legacy()
			&& !request.externalId().equals(syncState.getExternalId()))) {
			throw new SnapshotException(SnapshotErrorCode.OBSERVATION_TARGET_MISMATCH);
		}
		var completed = responseCaptureService.findProcessedResponse(captureId);
		if (completed.isPresent()) return completed.get();
		if (!menuEvidenceService.isLatest(observation, request) || !responseCaptureService.isLatest(request)) {
			return new SnapshotSubmitResponse(null, false, syncState.getContentHash(), captureId, "HELD", List.of("STALE_EVIDENCE"));
		}
		// 이미 지나간 배부의 늦은 도착 — 관측 원장에는 남았지만(별도 트랜잭션) 방문 카운터·스케줄·신호는
		// 그 값으로 움직이지 않는다. 그 사이에 새 배부가 더 최신 원본을 가져왔을 수 있다 (v3 P2-1)
		if (request.attemptSeq() != null && request.attemptSeq() < syncState.getAttemptSeq()) {
			log.info("늦은 attempt 무시 — store {} 제출 세대 {} < 현재 {}",
				request.storeId(), request.attemptSeq(), syncState.getAttemptSeq());
			return new SnapshotSubmitResponse(null, false, syncState.getContentHash(), captureId, "HELD", List.of("STALE_ATTEMPT"));
		}

		ParsedPlace parsedPlace = ResponseDiagnostics.parsePreservingInput(naverPlaceParser, request.payload());
		String contentHash = menuAllowed ? contentHashService.canonicalHash(parsedPlace) : syncState.getContentHash();
		String nonMenuHash = contentHashService.nonMenuHash(parsedPlace);
		String menuHash = menuAllowed ? contentHashService.menuHash(parsedPlace) : null;
		String baeminHash = baeminAllowed ? contentHashService.baeminMenuHash(parsedPlace) : null;
		LocalDateTime observedAt = observation.legacy() && request.fetchedAt() != null
			? request.fetchedAt() : observation.observedAt();
		RegularHoursTracker.Observation hours = regularHoursTracker.observe(
			syncState.getHoursKnown(), parsedPlace.businessHoursRaw(), observedAt);
		boolean changed;
		if (!menuAllowed || syncState.getScopeHashVersion() == ContentHashService.SCOPE_VERSION) {
			changed = !nonMenuHash.equals(syncState.getNonMenuHash()) || hours.changed()
				|| (menuAllowed && !menuHash.equals(syncState.getMenuHash()));
		} else if (syncState.getContentHashVersion() == ContentHashService.VERSION) {
			// 3세대: 영업시간 밖 내용은 해시로, 영업시간은 요일별 정기 값 기억과 공통 요일끼리 비교한다
			changed = !contentHash.equals(syncState.getContentHash()) || hours.changed();
			if (changed && contentHash.equals(syncState.getContentHash())) {
				log.debug("정기 영업시간 변경 — store {} 바뀐 요일 {} 처음 보인 요일 {}",
					request.storeId(), hours.changedDays(), hours.firstSeenDays());
			}
		} else {
			// 저장된 해시의 세대로 비교한다 — 세대가 올라간 직후의 재방문을 전부 "변경"으로 만들지 않기 위해서다.
			// 이 방문 뒤로는 3세대 해시와 정기 영업시간 기억으로 넘어간다
			changed = !contentHashService.hashFor(parsedPlace, syncState.getContentHashVersion())
				.equals(syncState.getContentHash());
		}

		changed |= baeminAllowed && !baeminHash.equals(syncState.getBaeminMenuHash());

		if (!changed) {
			if (menuAllowed) refreshSignal(request, parsedPlace, null, observedAt, observation.observationId(), false);
			syncState.recordScopedSuccess(false, menuAllowed ? contentHash : null, ContentHashService.VERSION,
				nonMenuHash, menuHash, ContentHashService.SCOPE_VERSION, observedAt);
			syncState.rememberHours(hours.knownAfter());
			syncState.recordBaeminObservation(baeminHash, observedAt);
			return new SnapshotSubmitResponse(null, false, contentHash);
		}

		PlaceSnapshot snapshot = placeSnapshotRepository.save(PlaceSnapshot.builder()
			.storeId(request.storeId())
			.source(request.source())
			.fetchedAt(observedAt)
			.contentHash(contentHashService.canonicalHash(parsedPlace))
			.contentHashVersion(ContentHashService.VERSION)
			.payload(request.payload().toString())
			.build());
		syncState.recordScopedSuccess(true, menuAllowed ? contentHash : null, ContentHashService.VERSION,
			nonMenuHash, menuHash, ContentHashService.SCOPE_VERSION, observedAt);
		syncState.rememberHours(hours.knownAfter());
		syncState.recordBaeminObservation(baeminHash, observedAt);
		if (menuAllowed) refreshSignal(request, parsedPlace, snapshot.getId(), observedAt, observation.observationId(), true);
		reconcileService.reconcile(request.storeId(), snapshot.getId(), parsedPlace, menuAllowed, baeminAllowed);
		return new SnapshotSubmitResponse(snapshot.getId(), true, contentHash);
	}

	/**
	 * 신호 갱신 — 수집 경로에서 바로 한다. 수동 rebuild-all 에만 맡기면 스냅샷이 저장되지 않은 방문의
	 * 리뷰 변화는 영원히 도달하지 못한다 (원본을 남기지 않았으므로 나중에 재파싱할 수도 없다).
	 *
	 * 실패를 삼키지 않는다: 이 트랜잭션이 rollback 되면 sync_state 의 next_due_at 도 되돌아가 그 가게가
	 * due 로 남고 워커가 다시 가져온다. 방문 자체는 별도 트랜잭션의 관측 원장에 이미 남아 있으므로
	 * 수집 사실을 잃지도 않는다. 파생 작업을 따로 예약해 재시도하는 구조는 P2-1 의 pipeline_item 몫이다.
	 */
	private void refreshSignal(SnapshotSubmitRequest request, ParsedPlace parsedPlace, Long snapshotId,
			LocalDateTime observedAt, String observationId, boolean payloadRetained) {
		if (request.source() != SyncSource.NAVER) {
			return;
		}
		storeSignalService.refreshFromObservation(request.storeId(), snapshotId,
			storeSignalParser.parse(request.payload()), observedAt, observationId, payloadRetained);
	}

	/**
	 * 해시 재베이스라인 — 해시 정책(정규화 규칙·파서)이 바뀔 때, 가게별 최신 스냅샷으로
	 * content_hash 를 다시 계산해 저장한다. 카운터·스케줄은 건드리지 않는다.
	 * 이것 없이 정책을 바꾸면 다음 스윕 전체가 가짜 "변경"으로 잡힌다.
	 *
	 * 3세대부터는 세대와 정기 영업시간 기억도 함께 세운다. 기억은 최근 스냅샷(최대 6개)을 오래된 순으로 겹쳐 만든다 —
	 * 최신 스냅샷 하나로만 세우면 그 관측에서 날짜가 붙은 요일(추석 주의 목·금·토)이 비어, 다음 방문 때 약 2만 곳이
	 * "처음 보이는 요일"로 한꺼번에 변경이 된다(09-27 1차 재기준 실측: 월 43,423곳 · 금 22,969곳).
	 * 최신 스냅샷 뒤의 무변경 방문은 내용이 같았다는 뜻이라 이 스냅샷들을 기준으로 써도 된다.
	 */
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public int rebaselineContentHashes() {
		// 57k건을 한 트랜잭션으로 묶으면 커넥션 수명·메모리에서 죽는다(09-04 실측: 11분 뒤 connection closed) — 가게별 트랜잭션
		TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
		record Target(Long storeId, SyncSource source) {}
		List<Target> targets = storeSyncStateRepository.findAll().stream()
			.map(state -> new Target(state.getStoreId(), state.getSource())).toList();
		int rebaselined = 0;
		int scanned = 0;
		for (Target target : targets) {
			Integer done = transactionTemplate.execute(status -> {
				StoreSyncState syncState = storeSyncStateRepository.findForSnapshotUpdate(target.storeId(), target.source()).orElse(null);
				if (syncState == null || syncState.getContentHash() == null) {
					return 0;
				}
				List<PlaceSnapshot> recent = placeSnapshotRepository
					.findTop6ByStoreIdAndSourceOrderByFetchedAtDesc(syncState.getStoreId(), syncState.getSource());
				if (recent.isEmpty()) {
					return 0;
				}
				PlaceSnapshot newest = recent.get(0);
				if (syncState.getStoreFieldsObservedAt() != null
					&& syncState.getStoreFieldsObservedAt().isAfter(newest.getFetchedAt())) return 0;
				var newestPayload = objectMapper.readTree(newest.getPayload());
				var newestReport = responseDiagnostics.inspect(new SnapshotSubmitRequest(syncState.getStoreId(),
					syncState.getSource(), newestPayload, null, "rebaseline-" + newest.getId(), syncState.getExternalId(),
					newest.getFetchedAt().atZone(java.time.ZoneId.of("Asia/Seoul")).toOffsetDateTime(), null));
				if (!newestReport.menuAllowed() || !responseCaptureService.isCurrentSnapshot(syncState.getStoreId(),
					syncState.getSource().name(), syncState.getExternalId(), newest.getFetchedAt(), newestPayload)) return 0;
				String hoursKnown = null;
				ParsedPlace latestParsed = null;
				for (int i = recent.size() - 1; i >= 0; i--) {   // 오래된 순 — 새 관측이 옛 값을 덮는다
					PlaceSnapshot snapshot = recent.get(i);
					var historicalPayload = objectMapper.readTree(snapshot.getPayload());
					if (responseDiagnostics.inspect(new SnapshotSubmitRequest(syncState.getStoreId(), syncState.getSource(),
						historicalPayload, null, "rebaseline-" + snapshot.getId(), syncState.getExternalId(),
						snapshot.getFetchedAt().atZone(java.time.ZoneId.of("Asia/Seoul")).toOffsetDateTime(), null)).held()) continue;
					ParsedPlace parsed = ResponseDiagnostics.parsePreservingInput(naverPlaceParser, historicalPayload);
					hoursKnown = regularHoursTracker.observe(hoursKnown, parsed.businessHoursRaw(), snapshot.getFetchedAt())
						.knownAfter();
					latestParsed = parsed;
				}
				hoursKnown = preserveNewerHours(hoursKnown, syncState.getHoursKnown());
				String canonicalHash = contentHashService.canonicalHash(latestParsed);
				if (canonicalHash.equals(syncState.getContentHash())
					&& syncState.getContentHashVersion() == ContentHashService.VERSION
					&& hoursKnown.equals(syncState.getHoursKnown())) {
					return 0;
				}
				syncState.rebaseline(canonicalHash, ContentHashService.VERSION, hoursKnown);
				return 1;
			});
			rebaselined += done != null ? done : 0;
			if (++scanned % 5000 == 0) {
				log.info("해시 재베이스라인 진행 — {}/{} 스캔, {}건 갱신", scanned, targets.size(), rebaselined);
			}
		}
		log.info("해시 재베이스라인 — {}건 갱신 (전체 {}건)", rebaselined, targets.size());
		return rebaselined;
	}

	/** A six-snapshot rebuild cannot discard known weekdays or newer unchanged observations. */
	private String preserveNewerHours(String rebuilt, String existing) {
		if (existing == null) return rebuilt;
		var result = (tools.jackson.databind.node.ObjectNode)objectMapper.readTree(rebuilt);
		for (var entry : objectMapper.readTree(existing).properties()) {
			var candidate = result.path(entry.getKey());
			String oldAt = entry.getValue().path("observedAt").asString(null);
			String candidateAt = candidate.path("observedAt").asString(null);
			if (candidate.isMissingNode() || oldAt == null || candidateAt == null
				|| LocalDateTime.parse(oldAt).isAfter(LocalDateTime.parse(candidateAt))) {
				result.set(entry.getKey(), entry.getValue());
			}
		}
		return objectMapper.writeValueAsString(result);
	}
}
