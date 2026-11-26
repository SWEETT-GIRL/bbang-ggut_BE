package sweetgirl.dataops.reconcile.service;

import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.dataops.proposal.dto.response.ChangeProposalResponse;
import sweetgirl.dataops.proposal.enums.ProposalProducer;
import sweetgirl.dataops.proposal.enums.ProposalTargetFamily;
import sweetgirl.dataops.proposal.repository.ChangeProposalRepository;
import sweetgirl.dataops.proposal.service.ProposalCommandService;
import sweetgirl.dataops.reconcile.entity.StoreFieldProvenance;
import sweetgirl.dataops.reconcile.enums.ProvenanceSource;
import sweetgirl.dataops.reconcile.policy.BeverageFilter;
import sweetgirl.dataops.reconcile.policy.MenuCategoryClassifier;
import sweetgirl.dataops.reconcile.policy.MergeDecision;
import sweetgirl.dataops.reconcile.policy.MergePolicy;
import sweetgirl.dataops.reconcile.repository.StoreFieldProvenanceRepository;
import sweetgirl.dataops.serving.client.ServingReader;
import sweetgirl.dataops.serving.dto.MenuCurrentValues;
import sweetgirl.dataops.serving.dto.StoreCurrentValues;
import sweetgirl.dataops.snapshot.dto.request.SnapshotSubmitRequest;
import sweetgirl.dataops.snapshot.entity.PlaceSnapshot;
import sweetgirl.dataops.snapshot.parser.BusinessHourRow;
import sweetgirl.dataops.snapshot.parser.BusinessHoursParser;
import sweetgirl.dataops.snapshot.parser.NaverPlaceParser;
import sweetgirl.dataops.snapshot.parser.ParsedMenu;
import sweetgirl.dataops.snapshot.parser.ParsedPlace;
import sweetgirl.dataops.snapshot.repository.PlaceSnapshotRepository;
import sweetgirl.dataops.snapshot.service.ResponseCaptureService;
import sweetgirl.dataops.snapshot.service.ResponseDiagnostics;
import sweetgirl.dataops.sync.entity.StoreSyncState;
import sweetgirl.dataops.sync.enums.SyncSource;
import sweetgirl.dataops.sync.repository.StoreSyncStateRepository;
import tools.jackson.databind.ObjectMapper;

/**
 * 판정층 오케스트레이터 — ParsedPlace vs 운영 현재값(ServingReader) 필드 단위 diff →
 * 병합 정책 적용 → proposal 수납. 자동 반영은 수집 트랜잭션 커밋 뒤 AutomaticApplyService가 별도로 검증한다.
 *
 * diff 범위: store.name / store.phone / store.instagram_handle / store.business_hours(요일 세트 비교) /
 * 신규 메뉴(음료 제외, 배민 단독 포함) / 메뉴 가격(네이버만 — 배민 가격은 배달가라 제외) / 메뉴 소멸.
 * 영업시간·인스타는 2026-08-14 확장 (운영에 store_business_hour 권한·instagram_handle 컬럼 확보 —
 * field-tracks-plan ①·③). 인스타 핸들은 운영 반영과 별개로 sync_state(INSTAGRAM) 추적을 유지한다.
 * 기존 메뉴 정책 재판정(제외 필터·종류 재분류)은 2026-09-19 추가 — reconcileMenuPolicy 참고.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ReconcileService {

	/** 이름 끝의 가격부 — 공백 뒤 '변동가격(업주문의)'·'변동가격'·'시가'·'3,500'·'3500원'·'4,300~6,200원' */
	private static final Pattern MENU_PRICE_SUFFIX = Pattern.compile(
		"\\s+(?:변동가격\\(업주문의\\)|변동가격|시가|[\\d,]+\\s*~\\s*[\\d,]+\\s*원?|\\d{1,3}(?:,\\d{3})+\\s*원?|\\d+원)\\s*$");
	/**
	 * 이름 끝의 재고 표시 — 배민 메뉴가 '햄치즈소금빵(남은수량2개)' · '마틸다 초코 케이크 1조각 남았어요(1조각)' 처럼 남은 수량을 이름에 싣는다.
	 * 방문마다 숫자가 바뀌어 같은 메뉴가 새 메뉴·소멸로 되풀이되므로 비교에서 떼고, 새 메뉴 이름에서도 뗀다 (09-20 표본 267583 · 132537)
	 */
	private static final Pattern MENU_STOCK_SUFFIX = Pattern.compile(
		"\\s*(?:\\(\\s*남은\\s*수량\\s*\\d+\\s*(?:개|세트|조각)?\\s*\\)|\\d+\\s*조각\\s*남았어요\\s*(?:\\([^)]*\\))?)\\s*$");

	private final ObjectProvider<ServingReader> servingReaderProvider;
	private final ProposalCommandService proposalCommandService;
	private final MergePolicy mergePolicy;
	private final BeverageFilter beverageFilter;
	private final MenuCategoryClassifier menuCategoryClassifier;
	private final StoreFieldProvenanceRepository provenanceRepository;
	private final StoreSyncStateRepository storeSyncStateRepository;
	private final ChangeProposalRepository changeProposalRepository;
	private final PlaceSnapshotRepository placeSnapshotRepository;
	private final NaverPlaceParser naverPlaceParser;
	private final BusinessHoursParser businessHoursParser;
	private final ResponseDiagnostics responseDiagnostics;
	private final ResponseCaptureService responseCaptureService;
	private final org.springframework.transaction.PlatformTransactionManager transactionManager;
	private final ObjectMapper objectMapper;

	@Value("${dataops.reconcile.enabled:true}")
	private boolean enabled;

	private static final List<String> WEEK = List.of(
		"MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY");

	/** 좌표 이동 신호 임계값 — 이 미만은 지오코딩 오차로 무시 (field-tracks-plan ②) */
	private static final double ADDRESS_DISTANCE_THRESHOLD_METERS = 150d;

	/** 뭉뚱그려진 종류 — 여기(와 NULL)에 있는 메뉴만 재분류 대상이다. 구체 종류는 AI·어드민·사용자가 고른 것일 수 있어 덮지 않는다 */
	private static final Set<String> GENERIC_MENU_CATEGORIES = Set.of("빵", "기타");

	/**
	 * 이 판정에 쓴 정책의 버전 — 병합 정책(MergePolicy)·제외 필터(BeverageFilter)·종류 분류(MenuCategoryClassifier)
	 * 중 하나라도 바뀌면 올린다. 같은 결과가 나왔을 때 "정책이 바뀌었는데 결과가 같다"와 "판정을 안 했다"를 가른다.
	 */
	private static final String POLICY_VERSION = "naver-reconcile-2026-11-26-menu-sources";

	/**
	 * 이 판정이 실제로 다시 보는 묶음 — 범위 정리의 경계다.
	 * 여기 없는 묶음(지역·상태·분류·매칭)은 다른 producer 의 것이라 건드리지 않는다.
	 * 새 판정을 추가하면 이 목록도 함께 늘려야 한다 — 빼먹으면 그 묶음의 이전 세대가 영원히 남는다.
	 */
	private static final List<ProposalTargetFamily> JUDGED_FAMILIES = List.of(
		ProposalTargetFamily.STORE_NAME,
		ProposalTargetFamily.STORE_PHONE,
		ProposalTargetFamily.STORE_INSTAGRAM,
		ProposalTargetFamily.STORE_ADDRESS,
		ProposalTargetFamily.BUSINESS_HOURS,
		ProposalTargetFamily.MENU_CONTENT,
		ProposalTargetFamily.MENU_REMOVAL,
		ProposalTargetFamily.MENU_CATEGORY);

	private static final List<ProposalTargetFamily> STORE_JUDGED_FAMILIES = JUDGED_FAMILIES.stream()
		.filter(family -> family != ProposalTargetFamily.MENU_CONTENT
			&& family != ProposalTargetFamily.MENU_REMOVAL && family != ProposalTargetFamily.MENU_CATEGORY)
		.toList();

	/**
	 * 정책 재판정 소멸 제안의 old_value.reason — 네이버에서 사라진 소멸 제안(reason 없음)과 구분해 따로 검수·승인할 수 있게.
	 * 일괄 승인은 반드시 scripts/menu_exclude_cleanup.py 의 hold_reason() 을 승인 시점에 운영 값(좋아요·빵 기록)으로 돌릴 것 —
	 * 필터에 알려진 오탐('소문의 ○○' 가게 고유 메뉴명 · '테이크아웃 N입' 묶음 등)이 이 제안에 섞인다.
	 */
	static final String EXCLUDED_BY_POLICY_REASON = "제외 필터";

	/**
	 * 재판정 — 병합 정책(음료 필터 등)이 바뀔 때, 저장된 최신 스냅샷으로 다시 판정한다. 재크롤 불요(원본층 재사용).
	 *
	 * 정리 범위 (v3 P1-2): 가게별 판정이 끝난 뒤, 그 가게에서 <b>이 producer 가 이번에 본 묶음</b>의
	 * PENDING 중 이번 판정에 나오지 않은 것만 SUPERSEDED 로 넘긴다. 이전 구현은 PENDING 전역 삭제라서
	 * 재판정 한 번이 공공데이터 폐업 제안·매칭 제안·지역 제안까지 함께 날렸다.
	 * APPROVED/REJECTED/APPLYING 과 다른 producer 의 제안은 건드리지 않는다.
	 *
	 * ⚠️ 트랜잭션 경계 주의: 전체를 한 트랜잭션에 묶으면 8천 곳의 운영 DB 왕복 + 누적 미커밋 행으로
	 * 초당 <1건까지 느려진다(실측). 그래서 이 메서드는 NOT_SUPPORTED 로 트랜잭션 밖에서 돌고,
	 * 삭제 1회 + 가게별 1커밋을 TransactionTemplate 으로 나눈다 — 크롤 때의 "가게=요청=트랜잭션"과 동일.
	 * 반환: 재판정한 가게 수.
	 */
	@org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
	public int reconcileAllFromSnapshots() {
		org.springframework.transaction.support.TransactionTemplate transactionTemplate = new org.springframework.transaction.support.TransactionTemplate(
			transactionManager);
		List<Long> storeIds = placeSnapshotRepository.findDistinctStoreIdsBySource(SyncSource.NAVER);
		int reconciled = 0;
		for (Long storeId : storeIds) {
			try {
				Integer done = transactionTemplate.execute(status -> {
					PlaceSnapshot latest = placeSnapshotRepository
						.findTopByStoreIdAndSourceOrderByFetchedAtDesc(storeId, SyncSource.NAVER)
						.orElse(null);
					if (latest == null) {
						return 0;
					}
					ValidatedSnapshot validated = validateSnapshot(storeId, latest);
					if (validated == null) return 0;
					reconcile(storeId, latest.getId(), validated.parsed(), validated.menuAllowed(), validated.baeminAllowed());
					return 1;
				});
				reconciled += done != null ? done : 0;
			} catch (RuntimeException e) {
				log.warn("재판정 실패 — store {}: {}", storeId, e.getMessage());
			}
			if (reconciled % 500 == 0 && reconciled > 0) {
				log.info("재판정 진행 — {}/{}", reconciled, storeIds.size());
			}
		}
		log.info("재판정 완료 — {}곳 재판정 (가게별로 이 producer 의 이전 세대만 정리)", reconciled);
		return reconciled;
	}

	/** 영업시간만 재판정한 결과 — withProposal 은 판정 뒤 그 가게에 PENDING 영업시간 제안이 있는 수(기존 것 포함) */
	public record HoursRejudgeResult(int requested, int judged, int withProposal, int skipped, int failed) {
	}

	/**
	 * 영업시간만 재판정 — 영업시간 파서가 바뀌어 그 판정만 달라질 때 (예: "매일" 한 줄을 7요일로 펴기, 2026-09-27).
	 *
	 * 저장된 최신 스냅샷으로 {@link #reconcileBusinessHours} 만 돌린다. 정리 범위도 이 producer 의 BUSINESS_HOURS
	 * 묶음뿐이다 — 메뉴·이름·주소 등 다른 묶음의 제안은 건드리지 않는다({@link #reconcileAllFromSnapshots} 는 전 묶음을 다시 본다).
	 * 운영에 없는 가게·DELETED 가게·페이지 없는 스냅샷은 건너뛴다. 가게별 1커밋, 한 가게의 실패가 나머지를 막지 않는다.
	 */
	@org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
	public HoursRejudgeResult reconcileHoursFromSnapshots(List<Long> storeIds) {
		ServingReader servingReader = servingReaderProvider.getIfAvailable();
		if (servingReader == null) {
			throw new sweetgirl.dataops.serving.exception.ServingException(
				sweetgirl.dataops.serving.code.ServingErrorCode.PROD_DB_NOT_CONFIGURED);
		}
		if (!enabled) {
			return new HoursRejudgeResult(storeIds.size(), 0, 0, storeIds.size(), 0);
		}
		org.springframework.transaction.support.TransactionTemplate transactionTemplate =
			new org.springframework.transaction.support.TransactionTemplate(transactionManager);
		int judged = 0;
		int withProposal = 0;
		int skipped = 0;
		int failed = 0;
		for (Long storeId : storeIds) {
			try {
				Integer survivors = transactionTemplate.execute(status -> {
					PlaceSnapshot latest = placeSnapshotRepository
						.findTopByStoreIdAndSourceOrderByFetchedAtDesc(storeId, SyncSource.NAVER)
						.orElse(null);
					if (latest == null) {
						return null;
					}
					ValidatedSnapshot validated = validateSnapshot(storeId, latest);
					if (validated == null) return null;
					ParsedPlace parsedPlace = validated.parsed();
					Optional<StoreCurrentValues> current = servingReader.findStore(storeId);
					if (parsedPlace.pageMissing() || current.isEmpty() || "DELETED".equals(current.get().status())) {
						return null;
					}
					ReconcileGeneration generation = new ReconcileGeneration(latest.getId(), UUID.randomUUID(),
						new HashSet<>());
					reconcileBusinessHours(storeId, generation, parsedPlace, servingReader, provenanceByField(storeId));
					changeProposalRepository.expireOutOfGeneration(storeId,
						ProposalProducer.NAVER_SNAPSHOT_RECONCILE, List.of(ProposalTargetFamily.BUSINESS_HOURS),
						generation.survivors().isEmpty() ? List.of(0L) : generation.survivors(),
						generation.survivors().size(), "영업시간 재판정에서 더 이상 나오지 않음 — run " + generation.runId());
					return generation.survivors().size();
				});
				if (survivors == null) {
					skipped++;
				} else {
					judged++;
					withProposal += survivors > 0 ? 1 : 0;
				}
			} catch (RuntimeException e) {
				failed++;
				log.warn("영업시간 재판정 실패 — store {}: {}", storeId, e.getMessage());
			}
			int done = judged + skipped + failed;
			if (done % 500 == 0) {
				log.info("영업시간 재판정 진행 — {}/{} (제안 있음 {})", done, storeIds.size(), withProposal);
			}
		}
		log.info("영업시간 재판정 완료 — 요청 {}, 판정 {}, 제안 있음 {}, 건너뜀 {}, 실패 {}",
			storeIds.size(), judged, withProposal, skipped, failed);
		return new HoursRejudgeResult(storeIds.size(), judged, withProposal, skipped, failed);
	}

	private Map<String, ProvenanceSource> provenanceByField(Long storeId) {
		Map<String, ProvenanceSource> provenanceByField = new HashMap<>();
		for (StoreFieldProvenance provenance : provenanceRepository.findByStoreId(storeId)) {
			provenanceByField.put(provenance.getField(), provenance.getSource());
		}
		return provenanceByField;
	}

	/** Compatibility/manual entry: caller parsing cannot widen the authority of the saved raw. */
	public void reconcile(Long storeId, Long snapshotId, ParsedPlace parsedPlace) {
		if (!enabled) return;
		PlaceSnapshot snapshot = placeSnapshotRepository.findById(snapshotId).orElse(null);
		ValidatedSnapshot validated = snapshot == null ? null : validateSnapshot(storeId, snapshot);
		if (validated != null) reconcile(storeId, snapshotId, validated.parsed(), validated.menuAllowed(), validated.baeminAllowed());
	}

	/** Live submission entry; menuAllowed must come from the same raw's ResponseDiagnostics report. */
	public void reconcile(Long storeId, Long snapshotId, ParsedPlace parsedPlace, boolean menuAllowed) {
		reconcile(storeId, snapshotId, parsedPlace, menuAllowed, menuAllowed);
	}

	public void reconcile(Long storeId, Long snapshotId, ParsedPlace parsedPlace, boolean menuAllowed, boolean baeminAllowed) {
		if (!enabled) {
			return;
		}
		if (parsedPlace.pageMissing()) {
			// 페이지가 없는 응답을 판정하면 '메뉴 전부 소멸'로 읽혀 메뉴마다 소멸 제안이 쌓인다(이브런치 33건).
			// 폐업은 인허가 폐업 + 네이버 부재의 다중 신호로만 판단한다 — scripts/localdata_status_delta.py
			log.info("네이버 페이지 없음 — 판정 건너뜀: store {} snapshot {}", storeId, snapshotId);
			return;
		}
		ServingReader servingReader = servingReaderProvider.getIfAvailable();
		if (servingReader == null) {
			return;
		}
		Optional<StoreCurrentValues> current = servingReader.findStore(storeId);
		if (current.isEmpty()) {
			return;
		}

		Map<String, ProvenanceSource> provenanceByField = provenanceByField(storeId);

		ReconcileGeneration generation = new ReconcileGeneration(snapshotId, UUID.randomUUID(), new HashSet<>());
		reconcileStoreFields(storeId, generation, parsedPlace, current.get(), provenanceByField);
		reconcileBusinessHours(storeId, generation, parsedPlace, servingReader, provenanceByField);
		if (menuAllowed || baeminAllowed) {
			reconcileMenus(storeId, generation, parsedPlace, servingReader.findMenus(storeId), provenanceByField, menuAllowed, baeminAllowed);
		}
		upsertInstagramHandle(storeId, parsedPlace.instagramHandle());

		// 이 가게의 판정이 전부 끝난 뒤에만 이전 세대를 정리한다 — 중간에 예외가 나면 여기 오지 않으므로
		// 그 가게의 이전 제안이 그대로 남는다 (AC-09). 범위는 이 가게 × 이 producer × 이번에 본 묶음뿐이다
		int expired = changeProposalRepository.expireOutOfGeneration(storeId,
			ProposalProducer.NAVER_SNAPSHOT_RECONCILE, STORE_JUDGED_FAMILIES,
			generation.survivors().isEmpty() ? List.of(0L) : generation.survivors(),
			generation.survivors().size(), "재판정에서 더 이상 나오지 않음 — run " + generation.runId());
		if (expired > 0) {
			log.debug("이전 세대 정리 — store {} {}건", storeId, expired);
		}
	}

	/** Capture replay does not fabricate a new observation or alter old proposal origin. */
	public record ReplayProposal(long proposalId, boolean created, String producer, Long originalSnapshotId,
			UUID originalReconcileRunId, String target, String oldValue, String newValue, String reviewHoldReason) {}

	public String replayPolicyDigest() {
		return sweetgirl.dataops.snapshot.service.ResponseDigests.sha256("capture-replay-policy-v1\n" + POLICY_VERSION + "\n"
			+ sweetgirl.dataops.snapshot.service.ResponseDigests.classes(ReconcileService.class, MergePolicy.class,
				BeverageFilter.class, MenuCategoryClassifier.class, BusinessHoursParser.class, ProposalCommandService.class));
	}

	/** Internal proposal-only hook. Caller must verify approval and hold the store observation/state locks. */
	public List<ReplayProposal> reconcileCapturedReplay(Long storeId, ParsedPlace parsedPlace, boolean menuAllowed) {
		return reconcileCapturedReplay(storeId, parsedPlace, menuAllowed, menuAllowed);
	}

	public List<ReplayProposal> reconcileCapturedReplay(Long storeId, ParsedPlace parsedPlace, boolean menuAllowed, boolean baeminAllowed) {
		if (!enabled) throw new IllegalStateException("RECONCILE_DISABLED");
		if (parsedPlace.pageMissing()) throw new IllegalStateException("REPLAY_PAGE_ABSENT");
		ServingReader reader = servingReaderProvider.getIfAvailable();
		if (reader == null) throw new IllegalStateException("SERVING_READ_UNAVAILABLE");
		StoreCurrentValues current = reader.findStore(storeId)
			.orElseThrow(() -> new IllegalStateException("SERVING_STORE_MISSING"));
		if ("DELETED".equals(current.status())) throw new IllegalStateException("SERVING_STORE_DELETED");
		var generation = new ReconcileGeneration(null, UUID.randomUUID(), new HashSet<>());
		Map<String, ProvenanceSource> provenance = provenanceByField(storeId);
		reconcileStoreFields(storeId, generation, parsedPlace, current, provenance);
		reconcileBusinessHours(storeId, generation, parsedPlace, reader, provenance);
		if (menuAllowed || baeminAllowed) reconcileMenus(storeId, generation, parsedPlace, reader.findMenus(storeId), provenance, menuAllowed, baeminAllowed);
		// No Instagram sync-state mutation and no out-of-generation expiration in scoped replay.
		return generation.survivors().stream().sorted().map(id -> {
			var proposal = changeProposalRepository.findById(id)
				.orElseThrow(() -> new IllegalStateException("REPLAY_PROPOSAL_NOT_VISIBLE"));
			return new ReplayProposal(id, generation.runId().equals(proposal.getReconcileRunId()),
				proposal.getProducer().name(), proposal.getSnapshotId(), proposal.getReconcileRunId(),
				proposal.getTarget(), proposal.getOldValue(), proposal.getNewValue(),
				proposal.getTarget().matches("menu\\.\\d+\\.removed")
					? "REPLAY_MENU_REMOVAL_REQUIRES_MANIFEST_GATE" : null);
		}).toList();
	}

	private record ValidatedSnapshot(ParsedPlace parsed, boolean menuAllowed, boolean baeminAllowed) {}

	/** Replays share the live submit state lock, then recheck current identity, raw evidence and diagnostics. */
	private ValidatedSnapshot validateSnapshot(Long storeId, PlaceSnapshot snapshot) {
		if (!storeId.equals(snapshot.getStoreId()) || snapshot.getSource() != SyncSource.NAVER
			|| snapshot.getFetchedAt() == null) return null;
		StoreSyncState state = storeSyncStateRepository.findForSnapshotUpdate(storeId, SyncSource.NAVER)
			.orElse(null);
		if (state == null || state.getExternalId() == null) return null;
		var payload = objectMapper.readTree(snapshot.getPayload());
		if (!responseCaptureService.isCurrentSnapshot(storeId, SyncSource.NAVER.name(), state.getExternalId(),
			snapshot.getFetchedAt(), payload)) {
			log.info("최신 원본과 다른 스냅샷 — 재판정 보류: store {} snapshot {}", storeId, snapshot.getId());
			return null;
		}
		var request = new SnapshotSubmitRequest(storeId, SyncSource.NAVER, payload, snapshot.getFetchedAt(),
			"snapshot-" + snapshot.getId(), state.getExternalId(),
			snapshot.getFetchedAt().atZone(ZoneId.of("Asia/Seoul")).toOffsetDateTime(), null);
		ResponseDiagnostics.Report report = responseDiagnostics.inspect(request);
		if (report.held()) {
			log.info("응답 검증 실패 — 재판정 보류: store {} snapshot {}", storeId, snapshot.getId());
			return null;
		}
		return new ValidatedSnapshot(ResponseDiagnostics.parsePreservingInput(naverPlaceParser, payload), report.menuAllowed(), report.baeminAllowed());
	}

	/** 한 가게의 한 판정 실행 — 이번 세대에서 살아남은 제안 id 를 모은다 (v3 P1-2) */
	/** Called with a verified, completed live capture under the store observation barrier. */
	public List<sweetgirl.dataops.autoapply.AutomaticCandidate> reconcileAutomatic(Long storeId, Long snapshotId,
			ParsedPlace parsed, boolean menuAllowed, boolean baeminAllowed) {
		if (!enabled || parsed.pageMissing()) throw new sweetgirl.dataops.autoapply.AutomaticApplyHold("OBSERVATION_UNUSABLE");
		ServingReader reader = servingReaderProvider.getIfAvailable();
		if (reader == null) throw new IllegalStateException("SERVING_READ_UNAVAILABLE");
		StoreCurrentValues current = reader.findStore(storeId)
			.orElseThrow(() -> new sweetgirl.dataops.autoapply.AutomaticApplyHold("STORE_MISSING"));
		if (!"ACTIVE".equals(current.status())) throw new sweetgirl.dataops.autoapply.AutomaticApplyHold("STORE_INACTIVE");
		var generation = new ReconcileGeneration(snapshotId, UUID.randomUUID(), new HashSet<>());
		var provenance = provenanceByField(storeId);
		reconcileStoreFields(storeId, generation, parsed, current, provenance);
		reconcileBusinessHours(storeId, generation, parsed, reader, provenance);
		if (menuAllowed || baeminAllowed)
			reconcileMenus(storeId, generation, parsed, reader.findMenus(storeId), provenance, menuAllowed, baeminAllowed);
		return List.copyOf(generation.candidates().values());
	}

	private record ReconcileGeneration(Long snapshotId, UUID runId, Set<Long> survivors,
			Map<Long, sweetgirl.dataops.autoapply.AutomaticCandidate> candidates) {
		ReconcileGeneration(Long snapshotId, UUID runId, Set<Long> survivors) {
			this(snapshotId, runId, survivors, new LinkedHashMap<>());
		}
		void candidate(Long id, String target, String oldValue, String newValue, ProvenanceSource source) {
			candidates.put(id, new sweetgirl.dataops.autoapply.AutomaticCandidate(id, target, oldValue, newValue, source));
		}
	}

	private void reconcileStoreFields(Long storeId, ReconcileGeneration generation, ParsedPlace parsedPlace,
			StoreCurrentValues current, Map<String, ProvenanceSource> provenanceByField) {
		if (parsedPlace.name() != null && !normalizeName(parsedPlace.name()).equals(normalizeName(current.name()))) {
			propose(storeId, generation, "store.name", jsonText(current.name()), jsonText(parsedPlace.name()),
				provenanceByField.get("name"));
		}
		if (parsedPlace.phone() != null
			&& !normalizePhone(parsedPlace.phone()).equals(normalizePhone(current.phone()))) {
			propose(storeId, generation, "store.phone", jsonText(current.phone()), jsonText(parsedPlace.phone()),
				provenanceByField.get("phone"));
		}
		if (parsedPlace.instagramHandle() != null
			&& !parsedPlace.instagramHandle().equalsIgnoreCase(nullToEmpty(current.instagramHandle()))) {
			propose(storeId, generation, "store.instagram_handle", jsonText(current.instagramHandle()),
				jsonText(parsedPlace.instagramHandle()), provenanceByField.get("instagram_handle"));
		}
		reconcileAddress(storeId, generation, parsedPlace, current, provenanceByField);
	}

	/**
	 * 주소·좌표 — 이전(移轉)·오매칭 감지 (field-tracks-plan ②).
	 * **좌표가 1차 신호다**: 150m 이내면 같은 곳으로 보고 주소 표기 차이는 무시한다
	 * (우리 DB 도로명 vs 네이버 지번, 시도명 이형 — 문자열 비교는 오탐 6,630건을 만든 실측).
	 * 좌표 비교가 불가능할 때만 주소 문자열(지번/도로명 정규화)로 폴백한다.
	 * 반영 주소는 도로명(roadAddress) 우선 — 운영 DB 관행과 일치.
	 */
	private void reconcileAddress(Long storeId, ReconcileGeneration generation, ParsedPlace parsedPlace,
			StoreCurrentValues current, Map<String, ProvenanceSource> provenanceByField) {
		Double distanceMeters = distanceMeters(
			current.latitude(), current.longitude(), parsedPlace.latitude(), parsedPlace.longitude());
		if (distanceMeters != null) {
			if (distanceMeters <= ADDRESS_DISTANCE_THRESHOLD_METERS) {
				return; // 같은 자리 — 표기 차이는 이전이 아니다
			}
		} else {
			boolean addressDiffers = parsedPlace.address() != null && current.address() != null
				&& !matchesEitherAddress(current.address(), parsedPlace.address(), parsedPlace.roadAddress());
			if (!addressDiffers) {
				return;
			}
		}
		String newAddress = parsedPlace.roadAddress() != null ? parsedPlace.roadAddress() : parsedPlace.address();
		propose(storeId, generation, "store.address",
			addressJson(current.address(), current.longitude(), current.latitude(), null),
			addressJson(newAddress, parsedPlace.longitude(), parsedPlace.latitude(), distanceMeters),
			provenanceByField.get("address"));
	}

	private boolean matchesEitherAddress(String currentAddress, String address, String roadAddress) {
		String normalizedCurrent = normalizeName(currentAddress);
		return (address != null && normalizeName(address).equals(normalizedCurrent))
			|| (roadAddress != null && normalizeName(roadAddress).equals(normalizedCurrent));
	}

	/** 하버사인 거리(m) — 좌표가 한쪽이라도 없으면 null */
	private Double distanceMeters(Double lat1, Double lon1, Double lat2, Double lon2) {
		if (lat1 == null || lon1 == null || lat2 == null || lon2 == null) {
			return null;
		}
		double earthRadius = 6371000d;
		double dLat = Math.toRadians(lat2 - lat1);
		double dLon = Math.toRadians(lon2 - lon1);
		double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
			+ Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
			* Math.sin(dLon / 2) * Math.sin(dLon / 2);
		return earthRadius * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
	}

	private String addressJson(String address, Double longitude, Double latitude, Double distanceMeters) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("address", address);
		body.put("lon", longitude);
		body.put("lat", latitude);
		if (distanceMeters != null) {
			body.put("distanceM", Math.round(distanceMeters));
		}
		return objectMapper.writeValueAsString(body);
	}

	/** 영업시간 — 요일 세트 전체를 정규화 문자열로 비교한다 (요일별 부분 diff 는 과잉, 설계 field-tracks-plan ①) */
	private void reconcileBusinessHours(Long storeId, ReconcileGeneration generation, ParsedPlace parsedPlace,
			ServingReader servingReader, Map<String, ProvenanceSource> provenanceByField) {
		List<BusinessHourRow> parsed = businessHoursParser.parse(parsedPlace.businessHoursRaw());
		if (parsed.isEmpty()) {
			return;
		}
		List<BusinessHourRow> current = servingReader.findBusinessHours(storeId);
		// 네이버는 명절·임시 휴무 주에 그 요일을 "목(9/24)"처럼 날짜를 붙여 보여 주고, 파서는 그 요일을 건너뛴다.
		// 빠진 요일은 운영 값을 그대로 두고 읽힌 요일만 덮는다 — 빠진 요일이 '영업시간 삭제'로 제안되지 않게
		// (09-20 추석 주 표본 40곳 중 16곳이 목·금·토를 잃는 제안이었다)
		List<BusinessHourRow> merged = overlayHours(current, parsed);
		if (canonicalHours(merged).equals(canonicalHours(current))) {
			return;
		}
		propose(storeId, generation, "store.business_hours", hoursJson(current), hoursJson(merged),
			provenanceByField.get("business_hours"));
	}

	private List<BusinessHourRow> overlayHours(List<BusinessHourRow> current, List<BusinessHourRow> parsed) {
		Map<String, BusinessHourRow> byDay = new LinkedHashMap<>();
		for (String day : WEEK) {
			byDay.put(day, null);
		}
		current.forEach(row -> byDay.put(row.dayOfWeek(), row));
		parsed.forEach(row -> byDay.put(row.dayOfWeek(), row));
		return byDay.values().stream().filter(java.util.Objects::nonNull).toList();
	}

	private String canonicalHours(List<BusinessHourRow> rows) {
		return rows.stream()
			.map(row -> row.dayOfWeek() + "|" + row.openTime() + "|" + row.closeTime() + "|" + row.closed())
			.sorted()
			.collect(java.util.stream.Collectors.joining(";"));
	}

	private String hoursJson(List<BusinessHourRow> rows) {
		List<Map<String, Object>> body = new java.util.ArrayList<>();
		for (BusinessHourRow row : rows) {
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("day", row.dayOfWeek());
			entry.put("open", row.openTime());
			entry.put("close", row.closeTime());
			entry.put("closed", row.closed());
			body.add(entry);
		}
		return objectMapper.writeValueAsString(body);
	}

	private String nullToEmpty(String value) {
		return value != null ? value : "";
	}

	private void reconcileMenus(Long storeId, ReconcileGeneration generation, ParsedPlace parsedPlace,
			List<MenuCurrentValues> currentMenus, Map<String, ProvenanceSource> provenance,
			boolean naverAllowed, boolean baeminAllowed) {
		Map<String, MenuCurrentValues> currentByName = new LinkedHashMap<>();
		for (MenuCurrentValues menu : currentMenus) currentByName.putIfAbsent(menuKey(menu.name()), menu);
		Map<String, ParsedMenu> naver = menusByName(naverAllowed ? parsedPlace.menus() : List.of());
		Map<String, ParsedMenu> baemin = menusByName(baeminAllowed ? parsedPlace.baeminMenus() : List.of());
		Set<String> observedBaeminTargets = new HashSet<>();
		Set<String> names = new java.util.LinkedHashSet<>(naver.keySet());
		names.addAll(baemin.keySet());
		for (String name : names) {
			MenuCurrentValues current = currentByName.get(name);
			if (current == null) {
				ParsedMenu menu = naver.containsKey(name) ? naver.get(name) : baemin.get(name);
				ProvenanceSource source = naver.containsKey(name) ? ProvenanceSource.NAVER : ProvenanceSource.BAEMIN;
				String target = "menu.add." + truncate(name, 50);
				if (source == ProvenanceSource.BAEMIN) observedBaeminTargets.add(target);
				if (!beverageFilter.isExcluded(menu.name(), menu.groupName()))
					proposeMenu(storeId, generation, target, null, menuJson(menu), source, provenance.get(target));
				continue;
			}
			reconcileMenuField(storeId, generation, current, naver.get(name), baemin.get(name), provenance, "price", observedBaeminTargets);
			if (!current.userAdded()) reconcileMenuField(storeId, generation, current, naver.get(name), baemin.get(name), provenance, "description", observedBaeminTargets);
		}
		// Delivery-list absence never authorizes deletion, including menus previously imported from Baemin.
		if (naverAllowed && !naver.isEmpty()) for (var entry : currentByName.entrySet()) {
			MenuCurrentValues menu = entry.getValue();
			if (!names.contains(entry.getKey()) && !excludedByPolicy(menu)
				&& !hasBaeminProvenance(menu.menuId(), provenance)) {
				propose(storeId, generation, "menu." + menu.menuId() + ".removed", menuJson(menu), null,
					provenance.get("menu." + menu.menuId()));
			}
		}
		if (naverAllowed) reconcileMenuPolicy(storeId, generation, currentMenus, provenance);
		if (generation.snapshotId() != null) {
			if (naverAllowed) expireMenuSource(storeId, generation, ProvenanceSource.NAVER, false, List.of(""),
				List.of(ProposalTargetFamily.MENU_CONTENT, ProposalTargetFamily.MENU_REMOVAL, ProposalTargetFamily.MENU_CATEGORY));
			if (baeminAllowed && !observedBaeminTargets.isEmpty()) expireMenuSource(storeId, generation, ProvenanceSource.BAEMIN,
				true, observedBaeminTargets, List.of(ProposalTargetFamily.MENU_CONTENT));
		}
	}

	private boolean hasBaeminProvenance(Long menuId, Map<String, ProvenanceSource> provenance) {
		String target = "menu." + menuId;
		return List.of(target, target + ".price", target + ".description").stream()
			.anyMatch(field -> provenance.get(field) == ProvenanceSource.BAEMIN);
	}

	private Map<String, ParsedMenu> menusByName(List<ParsedMenu> menus) {
		Map<String, ParsedMenu> byName = new LinkedHashMap<>();
		for (ParsedMenu menu : menus) if (menu.name() != null) byName.putIfAbsent(menuKey(menu.name()), menu);
		return byName;
	}

	private void reconcileMenuField(Long storeId, ReconcileGeneration generation, MenuCurrentValues current,
			ParsedMenu naver, ParsedMenu baemin, Map<String, ProvenanceSource> provenance, String field, Set<String> evaluatedBaeminTargets) {
		String target = "menu." + current.menuId() + "." + field;
		ProvenanceSource existing = provenance.getOrDefault(target, provenance.get("menu." + current.menuId()));
		// 필드가 사용 가능할 때 출처 연속성을 우선한다. 빈 필드는 다른 출처의 검토 후보로 보완한다.
		boolean naverUsable = usableMenuField(naver, field);
		boolean baeminUsable = usableMenuField(baemin, field);
		if (!naverUsable && !baeminUsable) return;
		boolean useBaemin = baeminUsable && (existing == ProvenanceSource.BAEMIN || !naverUsable);
		ParsedMenu observed = useBaemin ? baemin : naver;
		ProvenanceSource source = useBaemin ? ProvenanceSource.BAEMIN : ProvenanceSource.NAVER;
		if (useBaemin) evaluatedBaeminTargets.add(target);
		if (field.equals("price")) {
			int price = parsePrice(observed.price()).orElseThrow();
			if (!Integer.valueOf(price).equals(current.price()))
				proposeMenu(storeId, generation, target, jsonNumber(current.price()), jsonNumber(price), source, existing);
		} else {
			String description = normalizeDescription(observed.description());
			if (!description.isEmpty() && !description.equals(normalizeDescription(current.description())))
				proposeMenu(storeId, generation, target, jsonText(current.description()), jsonText(description), source, existing);
		}
	}

	private boolean usableMenuField(ParsedMenu menu, String field) {
		if (menu == null) return false;
		return field.equals("price") ? parsePrice(menu.price()).isPresent()
			: !normalizeDescription(menu.description()).isEmpty();
	}

	private void proposeMenu(Long storeId, ReconcileGeneration generation, String target, String oldValue,
			String newValue, ProvenanceSource source, ProvenanceSource existing) {
		if (mergePolicy.decide(target, source, existing) == MergeDecision.IGNORE) return;
		var response = proposalCommandService.propose(storeId, target, oldValue, newValue, source,
			generation.snapshotId(), ProposalProducer.NAVER_SNAPSHOT_RECONCILE, POLICY_VERSION, generation.runId());
		if (response == null) {
			rememberBlockedCandidate(storeId, generation, target, oldValue, newValue, source);
			return;
		}
		generation.survivors().add(response.proposalId());
		generation.candidate(response.proposalId(), target, oldValue, newValue, source);
		if (!target.startsWith("menu.add."))
			proposalCommandService.noteMenuSourceComparison(response.proposalId(), existing, source);
	}

	private void expireMenuSource(Long storeId, ReconcileGeneration generation, ProvenanceSource source,
			boolean observedOnly, java.util.Collection<String> targets, List<ProposalTargetFamily> families) {
		changeProposalRepository.expireMenuSourceOutOfGeneration(storeId, ProposalProducer.NAVER_SNAPSHOT_RECONCILE,
			source, families, observedOnly, targets, generation.survivors().isEmpty() ? List.of(0L) : generation.survivors(),
			generation.survivors().size(), "출처별 재판정 — " + source + " run " + generation.runId());
	}

	/**
	 * 기존 메뉴 정책 재판정 (2026-09-19) — 제외 필터(BeverageFilter)와 종류 분류(MenuCategoryClassifier)는 menu.add 때 한 번만
	 * 적용돼, 정책이 바뀌어도 이미 있는 메뉴는 그대로 남았다 (09-19 일회성 정리: 제외 62,583건(음료 36,073 · 식사 26,510) · 재분류 26,419건 —
	 * scripts/menu_exclude_cleanup.py · menu_reclassify.py). 매 스윕 지금 정책으로 다시 보고, 쓰지 않고 제안만 한다.
	 * <ul>
	 *   <li>제외 필터에 걸리면 menu.{id}.removed — 소멸 제안과 같은 모양에 old_value.reason 만 더한다. 재분류보다 우선</li>
	 *   <li>종류가 '빵'·'기타'·NULL 인데 분류기가 구체 종류를 주면 menu.{id}.category — 반영은 종류가 그대로일 때만</li>
	 * </ul>
	 * 네이버 스냅샷과 무관하게 운영 메뉴 행(이름·종류)만으로 판정한다. 사용자 등록 메뉴는 제안하지 않는다.
	 * 분류기가 판정을 믿기 어려우면(doubt) null 을 주므로 제안하지 않는다 — 미분류 큐(본 앱 AI)에 맡긴다.
	 * 거절된 제안은 ProposalCommandService.propose 가 같은 값으로 재상정하지 않는다.
	 */
	private void reconcileMenuPolicy(Long storeId, ReconcileGeneration generation, List<MenuCurrentValues> currentMenus,
			Map<String, ProvenanceSource> provenanceByField) {
		for (MenuCurrentValues menu : currentMenus) {
			if (menu.userAdded()) {
				continue;
			}
			if (excludedByPolicy(menu)) {
				propose(storeId, generation, "menu." + menu.menuId() + ".removed",
					policyRemovalJson(menu), null, provenanceByField.get("menu." + menu.menuId()));
				continue;
			}
			if (menu.category() != null && !GENERIC_MENU_CATEGORIES.contains(menu.category())) {
				continue;
			}
			String kind = menuCategoryClassifier.classify(menu.name());
			// 뭉뚱그린 종류로 되돌리는 제안은 내지 않는다 — '빵' 그물이 다시 생겨도 NULL 을 '빵'에 가두지 않게
			if (kind == null || GENERIC_MENU_CATEGORIES.contains(kind)) {
				continue;
			}
			String target = "menu." + menu.menuId() + ".category";
			propose(storeId, generation, target, categoryJson(menu), jsonCategory(kind), provenanceByField.get(target));
		}
	}

	/** 사용자 등록 메뉴는 제외 필터에 걸려도 지우자고 하지 않는다 — 회원이 올린 것 (menu_exclude_cleanup 과 같은 규칙) */
	private boolean excludedByPolicy(MenuCurrentValues menu) {
		return !menu.userAdded() && beverageFilter.isExcluded(menu.name(), null);
	}

	private void propose(Long storeId, ReconcileGeneration generation, String target, String oldValue, String newValue,
			ProvenanceSource existingSource) {
		MergeDecision decision = mergePolicy.decide(target, ProvenanceSource.NAVER, existingSource);
		if (decision == MergeDecision.IGNORE) {
			return;
		}
		// 수집 트랜잭션은 후보까지만 저장한다. 운영 쓰기는 별도 자동 반영 경로의 검증·점유 뒤 실행한다.
		// 살아남은 제안 id 를 모아 두면, 이번 판정에서 나오지 않은 이전 세대만 골라 expire 할 수 있다 (v3 P1-2)
		ChangeProposalResponse response = proposalCommandService.propose(storeId, target, oldValue, newValue,
			ProvenanceSource.NAVER, generation.snapshotId(), ProposalProducer.NAVER_SNAPSHOT_RECONCILE,
			POLICY_VERSION, generation.runId());
		if (response != null) {
			generation.survivors().add(response.proposalId());
			generation.candidate(response.proposalId(), target, oldValue, newValue, ProvenanceSource.NAVER);
		} else {
			rememberBlockedCandidate(storeId, generation, target, oldValue, newValue, ProvenanceSource.NAVER);
		}
	}

	/** Include an unresolved/conflicting target in automatic results without granting it write authority. */
	private void rememberBlockedCandidate(Long storeId, ReconcileGeneration generation, String target,
			String oldValue, String newValue, ProvenanceSource source) {
		changeProposalRepository.findFirstByStoreIdAndTargetAndStatusIn(storeId, target, List.of(
			sweetgirl.dataops.proposal.enums.ProposalStatus.PENDING, sweetgirl.dataops.proposal.enums.ProposalStatus.APPLYING,
			sweetgirl.dataops.proposal.enums.ProposalStatus.UNKNOWN))
			.ifPresent(candidate -> generation.candidate(candidate.getId(), target, oldValue, newValue, source));
	}

	/** 인스타 핸들 — 운영 스키마 불가침이라 sync_state(source=INSTAGRAM) 에만 저장, 스케줄은 잡지 않는다 */
	private void upsertInstagramHandle(Long storeId, String instagramHandle) {
		if (instagramHandle == null) {
			return;
		}
		Optional<StoreSyncState> existing = storeSyncStateRepository.findByStoreIdAndSource(storeId,
			SyncSource.INSTAGRAM);
		if (existing.isPresent()) {
			existing.get().updateExternalId(instagramHandle);
			return;
		}
		StoreSyncState instagramState = StoreSyncState.builder()
			.storeId(storeId)
			.source(SyncSource.INSTAGRAM)
			.externalId(instagramHandle)
			.build();
		instagramState.holdSchedule();
		storeSyncStateRepository.save(instagramState);
	}

	/**
	 * 메뉴 이름 키 — 옛 BE 크롤러가 이름 끝에 붙여 저장한 가격부('소금빵 변동가격(업주문의)', '칼도 4,300~6,200',
	 * '휘낭시에 3500원')를 떼고 정규화한다. 안 떼면 네이버의 깨끗한 이름과 키가 달라 같은 메뉴를 신규로 또
	 * 만든다 (2026-09-08 운영 실측: 가격부 붙은 이름 16,859건 중 14,155건이 9월 재수집에서 쌍둥이가 됐다).
	 */
	public static String menuKey(String name) {
		return normalizeName(name == null ? null : MENU_PRICE_SUFFIX.matcher(withoutStock(name)).replaceFirst(""));
	}

	static String withoutStock(String name) {
		return name == null ? null : MENU_STOCK_SUFFIX.matcher(name).replaceFirst("");
	}

	/** 문자·숫자만 남긴다 — Java 의 \W 는 한글까지 지우므로 유니코드 클래스(\p{L}\p{N})를 써야 한다 */
	private static String normalizeName(String name) {
		return name == null ? "" : name.replaceAll("[^\\p{L}\\p{N}]+", "").toLowerCase();
	}

	private String truncate(String value, int maxLength) {
		return value.length() <= maxLength ? value : value.substring(0, maxLength);
	}

	/** 메뉴 설명 비교용 — 공백 정리 후 운영 컬럼 길이(255)로 자른다 */
	private String normalizeDescription(String description) {
		if (description == null) {
			return "";
		}
		return truncate(description.replaceAll("\\s+", " ").trim(), 255);
	}

	private String normalizePhone(String phone) {
		return phone == null ? "" : phone.replaceAll("\\D", "");
	}

	private OptionalInt parsePrice(String price) {
		if (price == null || !price.matches("\\d{1,9}")) return OptionalInt.empty();
		return OptionalInt.of(Integer.parseInt(price));
	}

	private String jsonText(String value) {
		return objectMapper.writeValueAsString(value);
	}

	private String jsonNumber(Integer value) {
		return objectMapper.writeValueAsString(value);
	}

	private String menuJson(ParsedMenu menu) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("name", withoutStock(menu.name()));
		OptionalInt price = parsePrice(menu.price());
		body.put("price", price.isPresent() ? price.getAsInt() : null);
		body.put("description", menu.description());
		return objectMapper.writeValueAsString(body);
	}

	private String menuJson(MenuCurrentValues menu) {
		return objectMapper.writeValueAsString(menuBody(menu));
	}

	private Map<String, Object> menuBody(MenuCurrentValues menu) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("name", menu.name());
		body.put("price", menu.price());
		body.put("description", menu.description());
		return body;
	}

	/** 정책 소멸 — 소멸 제안 모양 + 지금 종류(구체 종류면 필터 오탐 신호)·reason */
	private String policyRemovalJson(MenuCurrentValues menu) {
		Map<String, Object> body = menuBody(menu);
		body.put("category", menu.category());
		body.put("reason", EXCLUDED_BY_POLICY_REASON);
		return objectMapper.writeValueAsString(body);
	}

	/** 재분류 old_value — categoryId 는 반영 시 compare-and-set 기준, 이름은 검수용 */
	private String categoryJson(MenuCurrentValues menu) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("name", menu.name());
		body.put("category", menu.category());
		body.put("categoryId", menu.categoryId());
		return objectMapper.writeValueAsString(body);
	}

	/** 재분류 new_value — 종류 이름만 (거절 이력 대조가 new_value 로 되므로 메뉴×종류마다 한 번 거절이면 끝난다) */
	private String jsonCategory(String kind) {
		return objectMapper.writeValueAsString(Map.of("category", kind));
	}
}
