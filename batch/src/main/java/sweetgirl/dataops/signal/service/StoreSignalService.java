package sweetgirl.dataops.signal.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.dataops.signal.code.SignalErrorCode;
import sweetgirl.dataops.signal.dto.response.StoreSignalResponse;
import sweetgirl.dataops.signal.entity.StoreSignal;
import sweetgirl.dataops.signal.exception.SignalException;
import sweetgirl.dataops.signal.repository.StoreSignalRepository;
import sweetgirl.dataops.snapshot.code.SnapshotErrorCode;
import sweetgirl.dataops.snapshot.dto.request.SnapshotSubmitRequest;
import sweetgirl.dataops.snapshot.entity.PlaceSnapshot;
import sweetgirl.dataops.snapshot.exception.SnapshotException;
import sweetgirl.dataops.snapshot.parser.ParsedStoreSignal;
import sweetgirl.dataops.snapshot.parser.StoreSignalParser;
import sweetgirl.dataops.snapshot.repository.PlaceSnapshotRepository;
import sweetgirl.dataops.snapshot.service.ResponseCaptureService;
import sweetgirl.dataops.snapshot.service.ResponseDiagnostics;
import sweetgirl.dataops.sync.repository.StoreSyncStateRepository;
import sweetgirl.dataops.sync.entity.StoreSyncState;
import sweetgirl.dataops.sync.enums.SyncSource;
import tools.jackson.databind.ObjectMapper;

/**
 * 가게 신호(태그 소스) 빌드 — StoreSignalParser 로 파싱해 store_signal 에 upsert 한다.
 * 파서 규칙이 바뀌면 PARSER_VERSION 을 올리고 rebuild-all (재크롤 불요 — 원본은 place_snapshot).
 * 전체 재빌드는 가게별 트랜잭션 — 한 트랜잭션으로 묶으면 페이로드 수만 건이 한 세션에 쌓여 붕괴한다 (재판정과 같은 이유).
 *
 * 관측에서 바로 갱신 (v3 P2-2): 내용 해시가 같아 스냅샷이 저장되지 않은 방문도 리뷰·신호는 바뀐다.
 * 그런 방문을 버리면 "메뉴가 그대로인 가게의 리뷰 갱신"이 신호까지 도달하지 못한다
 * (로컬 실측: NAVER 총 방문 126,659 중 무변경 22,013건 = 17.4%가 그렇게 버려졌다).
 * {@link #refreshFromObservation} 이 그 경로다 — 원본을 남기지 않으므로 payloadRetained=false 로 표시하고,
 * 재빌드가 그 행을 낡은 스냅샷으로 되돌리지 않게 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StoreSignalService {

	// 2: ratingReviewsTotal 추가 (2026-09-10). 별점 신뢰도의 분모라 리뷰 수와 따로 필요하다 —
	// 중앙값이 리뷰 수의 26% 뿐이고, 리뷰 590건에 별점 9건인 가게가 있다. 재크롤 없이 재파싱만 하면 된다.
	// 3: 영업시간 "매일" 한 줄을 7요일로 편다 (BusinessHoursParser, 2026-09-27). 8,421곳의 신호에 영업시간이 비어 있었다.
	//    역시 재파싱만 하면 된다 — rebuildAll 이 v2 행을 다시 읽는다
	static final int PARSER_VERSION = 3;

	private final StoreSignalRepository storeSignalRepository;
	private final PlaceSnapshotRepository placeSnapshotRepository;
	private final StoreSignalParser storeSignalParser;
	private final ObjectMapper objectMapper;
	private final PlatformTransactionManager transactionManager;
	private final StoreSyncStateRepository storeSyncStateRepository;
	private final ResponseDiagnostics responseDiagnostics;
	private final ResponseCaptureService responseCaptureService;

	/**
	 * 신호 해시 — 원본이 아니라 파서 결과(ParsedStoreSignal) 직렬화를 해싱한다.
	 * 원본의 키 순서·참조 표현 같은 잡음은 파서가 이미 걷어냈으므로, 이 해시가 달라지면 의미가 달라진 것이다.
	 */
	public String signalHash(ParsedStoreSignal signal) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(objectMapper.writeValueAsString(signal).getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new SignalException(SignalErrorCode.SIGNAL_HASH_FAILED, e.getMessage());
		}
	}

	/**
	 * 관측에서 바로 신호를 갱신한다 — 스냅샷이 저장되지 않은 방문에도 쓰인다.
	 *
	 * @param snapshotId 이 관측의 원본이 저장됐다면 그 스냅샷 id. null 이면 원본을 저장하지 않은 방문이므로
	 *                   기존 행의 snapshot_id 를 유지한다 (첫 관측이면서 null 이면 갱신하지 않는다)
	 * @param payloadRetained 이 관측의 원본이 place_snapshot 에 남았는지
	 * @return 신호가 실제로 바뀌었는지 — 같은 해시면 근거 시각만 전진시키고 false 를 준다
	 */
	@Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRED)
	public boolean refreshFromObservation(Long storeId, Long snapshotId, ParsedStoreSignal signal,
			LocalDateTime observedAt, String observationId, boolean payloadRetained) {
		String hash = signalHash(signal);
		StoreSignal stored = storeSignalRepository.findById(storeId).orElse(null);
		if (stored != null && stored.getSignalObservedAt() != null
			&& stored.getSignalObservedAt().isAfter(observedAt)) return false;
		if (stored == null && snapshotId == null) {
			return false;   // 원본도 기존 행도 없다 — 근거를 가리킬 곳이 없으므로 만들지 않는다
		}
		if (stored != null && hash.equals(stored.getSignalHash())) {
			// 값은 그대로 — 근거를 본 시각만 전진시킨다. downstream 재계산은 필요 없다
			stored.recordObservation(hash, observedAt, observationId, stored.isPayloadRetained() || payloadRetained);
			return false;
		}
		String signalsJson = objectMapper.writeValueAsString(signal);
		int reviewsTotal = signal.visitorReviewsTotal() != null ? signal.visitorReviewsTotal() : 0;
		if (stored == null) {
			storeSignalRepository.save(StoreSignal.builder()
				.storeId(storeId).snapshotId(snapshotId).fetchedAt(observedAt)
				.category(signal.category()).visitorReviewsScore(signal.visitorReviewsScore())
				.visitorReviewsTotal(reviewsTotal).recommendMenuCount(signal.recommendMenus().size())
				.newOpening(signal.newOpening()).signals(signalsJson).parserVersion(PARSER_VERSION)
				.signalHash(hash).signalObservedAt(observedAt).observationId(observationId)
				.payloadRetained(payloadRetained)
				.build());
			return true;
		}
		stored.refresh(snapshotId != null ? snapshotId : stored.getSnapshotId(), observedAt, signal.category(),
			signal.visitorReviewsScore(), reviewsTotal, signal.recommendMenus().size(), signal.newOpening(),
			signalsJson, PARSER_VERSION);
		stored.recordObservation(hash, observedAt, observationId, payloadRetained);
		return true;
	}

	@Transactional(readOnly = true)
	public StoreSignalResponse getSignal(Long storeId) {
		StoreSignal stored = storeSignalRepository.findById(storeId)
			.orElseThrow(() -> new SignalException(SignalErrorCode.SIGNAL_NOT_FOUND));
		return StoreSignalResponse.of(stored, objectMapper.readValue(stored.getSignals(), ParsedStoreSignal.class));
	}

	/** 미리보기 — 저장된 원본을 파서로만 돌려 보여준다 (저장 없음, 파서 눈검사용) */
	@Transactional(readOnly = true)
	public StoreSignalResponse preview(Long snapshotId) {
		PlaceSnapshot snapshot = placeSnapshotRepository.findById(snapshotId)
			.orElseThrow(() -> new SnapshotException(SnapshotErrorCode.SNAPSHOT_NOT_FOUND));
		return StoreSignalResponse.preview(snapshot, PARSER_VERSION, parse(snapshot));
	}

	@Transactional
	public StoreSignalResponse rebuild(Long storeId) {
		// Acquire the same lock as live submit before reading any mutable signal state.
		StoreSyncState state = storeSyncStateRepository.findForSnapshotUpdate(storeId, SyncSource.NAVER)
			.orElseThrow(() -> new SignalException(SignalErrorCode.SIGNAL_REBUILD_HELD));
		PlaceSnapshot latest = placeSnapshotRepository
			.findTopByStoreIdAndSourceOrderByFetchedAtDesc(storeId, SyncSource.NAVER)
			.orElseThrow(() -> new SnapshotException(SnapshotErrorCode.SNAPSHOT_NOT_FOUND));
		StoreSignal stored = storeSignalRepository.findById(storeId).orElse(null);
		if (wouldRevert(stored, latest) || !canRebuild(latest, state)) {
			throw new SignalException(SignalErrorCode.SIGNAL_REBUILD_HELD);
		}
		ParsedStoreSignal signal = parse(latest);
		return StoreSignalResponse.of(upsert(latest, signal), signal);
	}

	/**
	 * 스냅샷 있는 전 가게 재빌드 — 실패한 가게는 건너뛰고 로그로 남긴다 (한 가게 때문에 전체가 멈추지 않게).
	 *
	 * 이미 현재 파서 버전인 신호, 최신 신호보다 오래된 원본, 응답 검증이 보류됐거나 메뉴 완전성이 없는 원본,
	 * 더 최신 수집 원본과 다른 스냅샷을 건너뛴다. 신호는 근거 시각이 하나라 메뉴만 빼고 갱신하지 않는다.
	 * 반환: 재빌드한 가게 수.
	 */
	@Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
	public int rebuildAll() {
		TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
		List<Long> storeIds = placeSnapshotRepository.findDistinctStoreIdsBySource(SyncSource.NAVER);
		List<Long> reverting = new java.util.ArrayList<>();
		List<Long> held = new java.util.ArrayList<>();
		int rebuilt = 0;
		for (Long storeId : storeIds) {
			try {
				Integer done = transactionTemplate.execute(status -> {
					StoreSyncState state = storeSyncStateRepository.findForSnapshotUpdate(storeId, SyncSource.NAVER)
						.orElse(null);
					if (state == null) {
						held.add(storeId);
						return 0;
					}
					PlaceSnapshot latest = placeSnapshotRepository
						.findTopByStoreIdAndSourceOrderByFetchedAtDesc(storeId, SyncSource.NAVER)
						.orElse(null);
					if (latest == null) {
						return 0;
					}
					StoreSignal stored = storeSignalRepository.findById(storeId).orElse(null);
					if (stored != null && stored.getParserVersion() == PARSER_VERSION) {
						return 0;   // 미완료·버전 불일치만 처리한다
					}
					if (wouldRevert(stored, latest)) {
						reverting.add(storeId);
						return 0;
					}
					if (!canRebuild(latest, state)) {
						held.add(storeId);
						return 0;
					}
					upsert(latest, parse(latest));
					return 1;
				});
				rebuilt += done != null ? done : 0;
			} catch (RuntimeException e) {
				log.warn("신호 재빌드 실패 — store {}: {}", storeId, e.getMessage());
			}
			if (rebuilt % 1000 == 0 && rebuilt > 0) {
				log.info("신호 재빌드 진행 — {}/{}", rebuilt, storeIds.size());
			}
		}
		if (!reverting.isEmpty()) {
			// 최신 신호보다 오래된 스냅샷 — 이 원본으로 근거 시각을 되돌리지 않는다
			log.warn("신호 재빌드 건너뜀(최신 신호보다 오래된 원본) — {}곳: {}", reverting.size(),
				reverting.subList(0, Math.min(20, reverting.size())));
		}
		log.info("신호 재빌드 완료 — {}곳 (parser v{}), 재수집 대상 {}곳, 응답/최신성 보류 {}곳",
			rebuilt, PARSER_VERSION, reverting.size(), held.size());
		return rebuilt;
	}

	/** Signals currently share one provenance timestamp, so incomplete menus hold the entire signal row. */
	private boolean canRebuild(PlaceSnapshot snapshot, StoreSyncState state) {
		if (snapshot.getSource() != SyncSource.NAVER || snapshot.getFetchedAt() == null) return false;
		if (state.getExternalId() == null) return false;
		var payload = objectMapper.readTree(snapshot.getPayload());
		if (!responseCaptureService.isCurrentSnapshot(snapshot.getStoreId(), SyncSource.NAVER.name(),
			state.getExternalId(), snapshot.getFetchedAt(), payload)) return false;
		var request = new SnapshotSubmitRequest(snapshot.getStoreId(), SyncSource.NAVER, payload,
			snapshot.getFetchedAt(), "snapshot-" + snapshot.getId(), state.getExternalId(),
			snapshot.getFetchedAt().atZone(ZoneId.of("Asia/Seoul")).toOffsetDateTime(), null);
		var report = responseDiagnostics.inspect(request);
		return !report.held() && report.menuAllowed();
	}

	private ParsedStoreSignal parse(PlaceSnapshot snapshot) {
		return storeSignalParser.parse(objectMapper.readTree(snapshot.getPayload()));
	}

	private StoreSignal upsert(PlaceSnapshot latest, ParsedStoreSignal signal) {
		String signalsJson = objectMapper.writeValueAsString(signal);
		int reviewsTotal = signal.visitorReviewsTotal() != null ? signal.visitorReviewsTotal() : 0;
		StoreSignal stored = storeSignalRepository.findById(latest.getStoreId()).orElse(null);
		if (stored == null) {
			return storeSignalRepository.save(StoreSignal.builder()
				.storeId(latest.getStoreId())
				.snapshotId(latest.getId())
				.fetchedAt(latest.getFetchedAt())
				.category(signal.category())
				.visitorReviewsScore(signal.visitorReviewsScore())
				.visitorReviewsTotal(reviewsTotal)
				.recommendMenuCount(signal.recommendMenus().size())
				.newOpening(signal.newOpening())
				.signals(signalsJson)
				.parserVersion(PARSER_VERSION)
				.signalHash(signalHash(signal))
				.signalObservedAt(latest.getFetchedAt())
				.payloadRetained(true)
				.build());
		}
		stored.refresh(latest.getId(), latest.getFetchedAt(), signal.category(), signal.visitorReviewsScore(),
			reviewsTotal, signal.recommendMenus().size(), signal.newOpening(), signalsJson, PARSER_VERSION);
		stored.recordObservation(signalHash(signal), latest.getFetchedAt(), stored.getObservationId(), true);
		return stored;
	}

	/**
	 * 재빌드가 이 가게를 낡은 스냅샷으로 되돌리게 되는가 (v3 P2-2/P2-3).
	 *
	 * 신호 근거 시각이 스냅샷보다 나중이면 원본 보존 플래그와 무관하게 재파싱을 보류한다.
	 * 메뉴 보류 관측으로 저장된 원본도 기존 메뉴 신호의 근거를 대체할 수 없다.
	 */
	private boolean wouldRevert(StoreSignal stored, PlaceSnapshot latest) {
		return stored != null && stored.getSignalObservedAt() != null
			&& latest.getFetchedAt() != null && stored.getSignalObservedAt().isAfter(latest.getFetchedAt());
	}
}
