package sweetgirl.dataops.candidate.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.dataops.candidate.dto.request.CandidateRegisterRequest;
import sweetgirl.dataops.candidate.dto.response.CandidateRegisterPreview;
import sweetgirl.dataops.candidate.dto.response.CandidateRegisterResult;
import sweetgirl.dataops.candidate.entity.NewStoreCandidate;
import sweetgirl.dataops.candidate.enums.CandidateRegistrationState;
import sweetgirl.dataops.candidate.enums.CandidateStatus;
import sweetgirl.dataops.candidate.repository.NewStoreCandidateRepository;
import sweetgirl.dataops.reconcile.entity.StoreFieldProvenance;
import sweetgirl.dataops.reconcile.enums.ProvenanceSource;
import sweetgirl.dataops.reconcile.repository.StoreFieldProvenanceRepository;
import sweetgirl.dataops.region.dto.LocationEvidence;
import sweetgirl.dataops.region.service.RegionEvidenceFinder;
import sweetgirl.dataops.serving.client.ServingReader;
import sweetgirl.dataops.serving.client.ServingWriter;
import sweetgirl.dataops.serving.code.ServingErrorCode;
import sweetgirl.dataops.serving.enums.ApplyOrigin;
import sweetgirl.dataops.serving.exception.ServingException;
import sweetgirl.dataops.serving.service.ApplyLogCommandService;
import sweetgirl.dataops.sync.entity.StoreSyncState;
import sweetgirl.dataops.sync.enums.SyncSource;
import sweetgirl.dataops.sync.enums.SyncTier;
import sweetgirl.dataops.sync.repository.StoreSyncStateRepository;
import tools.jackson.databind.ObjectMapper;

/**
 * 신규 후보 등록 배치 — MATCHED 후보(네이버 검증 완료)를 운영 store 로 INSERT 한다.
 *
 * 매칭 배치 반영(MatchingApplyService)과 같은 골격: 항목별 실패 격리, 선점 확인, apply_log, provenance.
 * INSERT 특유의 변형 두 가지:
 *  ① apply_log 는 선기록이 아니라 "INSERT 직후 기록" — store_id NOT NULL 이라 생성 전엔 기록 불가.
 *    대신 운영 쓰기 전에 후보에 operation 을 발급해 둔다 (v3 P1-3) — 응답을 잃어도 이 후보가
 *    시도됐음이 남고, 재시도 때 ②의 선점 확인이 placeId 를 발견해 INSERT 없이 귀속한다.
 *  ② 선점 확인 = placeId 존재 검사. 이미 있으면(운영에서 그새 등록/역방향 추출 누락) INSERT 하지
 *    않고 기존 가게로 귀속 — 중복 등재 방지가 등록보다 우선한다.
 *
 * 등록 완료의 정의 (v3 P1-3): "운영에 가게가 있다"와 "등록이 끝났다"는 다르다.
 * 신규 INSERT 든 기존 가게 귀속이든 {@link #ensureRegistrationCompleted} 를 지나야 REGISTERED 로 닫힌다.
 * 이전에는 기존 placeId 귀속이 곧바로 REGISTERED 였고 sync_state 를 만들지 않아, 그 가게가 한 번도
 * 수집되지 않았다 (2026-08-25 실측 4건). 후속이 빠지면 NEEDS_RECOVERY 로 남겨 재실행이 보완한다.
 *
 * 지역의 출처 (v3 P4-4): 요청에 실린 값이 아니라 {@code region_resolution} 이다. 워커가 등록 좌표를
 * region_resolver 로 역산해 후보(CANDIDATE, 관리번호)의 근거로 남기고, 여기서는 그 좌표·지금 기준표·투영 버전의
 * RESOLVED 근거를 찾아 그 지역을 쓴다. 근거 id·법정동 코드는 apply_log 에 남는다.
 *
 * 판정({@link #decide})과 실행을 나눈다 — 미리보기({@link #preview})가 운영 쓰기 없이 같은 판정을 보여 준다.
 *
 * 보류 세 가지:
 *  - 등록 좌표의 법정동 근거가 없거나 해소되지 않은 신규 INSERT
 *  - DELETED 가게의 재활성. 이전에 왜 제외됐는지 검토가 필요하므로 별도 경로({@link #reactivateAfterReview})로 뺀다
 *  - 이미 운영에 만든 적이 있는 후보(registered_store_id 있음)인데 placeId 로 가게를 찾지 못한 경우.
 *    다시 INSERT 하면 중복 등재다 — 사람이 확인한다
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CandidateRegisterService {

	private final ObjectProvider<ServingReader> servingReaderProvider;
	private final ObjectProvider<ServingWriter> servingWriterProvider;
	private final NewStoreCandidateRepository candidateRepository;
	private final StoreSyncStateRepository storeSyncStateRepository;
	private final StoreFieldProvenanceRepository provenanceRepository;
	private final ApplyLogCommandService applyLogCommandService;
	private final RegionEvidenceFinder regionEvidenceFinder;
	private final ObjectMapper objectMapper;

	public CandidateRegisterResult registerBatch(CandidateRegisterRequest request) {
		ServingReader servingReader = servingReaderProvider.getIfAvailable();
		ServingWriter servingWriter = servingWriterProvider.getIfAvailable();
		if (servingReader == null || servingWriter == null) {
			throw new ServingException(ServingErrorCode.PROD_DB_NOT_CONFIGURED);
		}

		int registered = 0;
		int dedupResolved = 0;
		int recovered = 0;
		int held = 0;
		int unknown = 0;
		int skipped = 0;
		List<String> failedMgmtNos = new ArrayList<>();
		for (CandidateRegisterRequest.Item item : request.candidates()) {
			try {
				switch (registerOne(servingReader, servingWriter, item)) {
					case REGISTERED -> registered++;
					case DEDUP_RESOLVED -> dedupResolved++;
					case RECOVERED -> recovered++;
					case HELD -> held++;
					case UNKNOWN -> unknown++;
					case SKIPPED -> skipped++;
				}
			} catch (RuntimeException e) {
				failedMgmtNos.add(item.mgmtNo());
				log.warn("신규 등록 실패 — mgmtNo {}: {}", item.mgmtNo(), e.getMessage());
			}
		}
		log.info("신규 등록 배치 — 요청 {}, 등록 {}, 중복귀속 {}, 후속보완 {}, 보류 {}, 결과불명 {}, 스킵 {}, 실패 {}",
			request.candidates().size(), registered, dedupResolved, recovered, held, unknown,
			skipped, failedMgmtNos.size());
		return new CandidateRegisterResult(request.candidates().size(), registered, dedupResolved,
			recovered, held, unknown, skipped, failedMgmtNos.size(), failedMgmtNos);
	}

	private enum Outcome { REGISTERED, DEDUP_RESOLVED, RECOVERED, HELD, UNKNOWN, SKIPPED }

	/** 판정 — 운영·로컬 어디에도 쓰지 않는다. 미리보기와 등록이 같은 판정을 쓴다 */
	public enum Plan {
		/** 신규 INSERT — 근거의 지역을 쓴다 */
		INSERT,
		/** 같은 placeId 의 살아 있는 가게가 있다 — INSERT 없이 귀속 */
		DEDUP,
		/** 이미 운영에 있고 후속만 빠졌다 — 빠진 것만 보완 */
		RECOVER,
		HOLD_NO_LOCATION_EVIDENCE,
		HOLD_DELETED_STORE,
		/** 만든 적이 있는 후보인데 운영에서 placeId 로 찾지 못했다 — 다시 INSERT 하면 중복이다 */
		HOLD_STORE_NOT_FOUND,
		/** 운영 결과 불명 — 확인 전에는 다시 쓰지 않는다 */
		UNKNOWN,
		/** 큐에 없거나 매칭 전이거나 이미 끝났다 */
		SKIP
	}

	private record Decision(Plan plan, NewStoreCandidate candidate, ServingReader.StoreRef existing,
			LocationEvidence evidence, String reason) {
	}

	private Decision decide(ServingReader servingReader, CandidateRegisterRequest.Item item) {
		NewStoreCandidate candidate = candidateRepository.findByMgmtNo(item.mgmtNo()).orElse(null);
		if (candidate == null || candidate.getNaverPlaceId() == null) {
			return new Decision(Plan.SKIP, candidate, null, null, "큐에 없거나 매칭 전"); // 재실행 멱등
		}
		// 결과 불명은 확인 전에 다시 쓰지 않는다 (P3 의 자동 대사까지 보류)
		if (candidate.getRegistrationState() == CandidateRegistrationState.UNKNOWN) {
			return new Decision(Plan.UNKNOWN, candidate, null, null,
				"등록 결과 불명 — operation " + candidate.getRegistrationOperationId());
		}
		// 후속만 빠진 건은 status 가 REGISTERED 여도 다시 들어와 보완한다
		boolean recovering = candidate.getRegistrationState() == CandidateRegistrationState.NEEDS_RECOVERY
			|| candidate.getRegistrationState() == CandidateRegistrationState.STORE_PRESENT;
		if (candidate.getStatus() != CandidateStatus.MATCHED && !recovering) {
			return new Decision(Plan.SKIP, candidate, null, null, "MATCHED 가 아님 — " + candidate.getStatus());
		}

		Optional<ServingReader.StoreRef> existing =
			servingReader.findStoreByNaverPlaceId(candidate.getNaverPlaceId());
		if (existing.isPresent()) {
			ServingReader.StoreRef ref = existing.get();
			if ("DELETED".equals(ref.status())) {
				// 네이버에 살아있고 신규 인허가까지 있지만, 이 가게는 한 번 제외된 적이 있다.
				// 왜 제외됐는지 보지 않고 되살리면 같은 이유로 다시 지워질 가게를 다시 넣는다 — 검토 경로로 보낸다
				return new Decision(Plan.HOLD_DELETED_STORE, candidate, ref, null,
					"store " + ref.id() + " 가 DELETED — 이전 제외 사유 검토 후 reactivateAfterReview 로 진행");
			}
			return new Decision(recovering ? Plan.RECOVER : Plan.DEDUP, candidate, ref, null, null);
		}
		if (candidate.getRegisteredStoreId() != null) {
			return new Decision(Plan.HOLD_STORE_NOT_FOUND, candidate, null, null,
				"store " + candidate.getRegisteredStoreId() + " 를 만든 적이 있으나 운영에서 placeId "
					+ candidate.getNaverPlaceId() + " 로 찾지 못했습니다 — 다시 INSERT 하지 않고 확인을 기다립니다");
		}

		// 여기부터 신규 INSERT — 등록 좌표의 법정동 근거가 있어야 쓴다
		RegionEvidenceFinder.Lookup lookup = regionEvidenceFinder.lookup(RegionEvidenceFinder.CANDIDATE,
			candidate.getMgmtNo(), item.longitude(), item.latitude(), "등록 좌표");
		if (!lookup.found()) {
			return new Decision(Plan.HOLD_NO_LOCATION_EVIDENCE, candidate, null, null, lookup.missingReason());
		}
		return new Decision(Plan.INSERT, candidate, null, lookup.evidence(), null);
	}

	private Outcome registerOne(ServingReader servingReader, ServingWriter servingWriter,
			CandidateRegisterRequest.Item item) {
		Decision decision = decide(servingReader, item);
		NewStoreCandidate candidate = decision.candidate();
		switch (decision.plan()) {
			case SKIP -> {
				return Outcome.SKIPPED;
			}
			case UNKNOWN -> {
				log.warn("등록 결과 불명으로 보류 중 — mgmtNo {}: {}", item.mgmtNo(), decision.reason());
				return Outcome.UNKNOWN;
			}
			case HOLD_DELETED_STORE -> {
				candidate.hold(CandidateRegistrationState.HELD_DELETED_STORE, decision.reason());
				candidateRepository.save(candidate);
				return Outcome.HELD;
			}
			case HOLD_NO_LOCATION_EVIDENCE -> {
				candidate.hold(CandidateRegistrationState.HELD_NO_LOCATION_EVIDENCE, decision.reason());
				candidateRepository.save(candidate);
				return Outcome.HELD;
			}
			case HOLD_STORE_NOT_FOUND -> {
				// 상태는 그대로(복구 필요) 두고 사유만 남긴다 — 운영에 가게를 만든 사실은 지우지 않는다
				candidate.markNeedsRecovery(decision.reason());
				candidateRepository.save(candidate);
				log.warn("등록 복구 보류 — mgmtNo {}: {}", item.mgmtNo(), decision.reason());
				return Outcome.HELD;
			}
			case DEDUP, RECOVER -> {
				// 기존 가게로 귀속 — 위치를 쓰지 않으므로 위치 근거를 요구하지 않고, 검증 완료로 표시하지도 않는다.
				// 생성 provenance 도 남기지 않는다 (이 가게를 우리가 만든 것이 아니다)
				long storeId = decision.existing().id();
				candidate.markStorePresent(storeId, false);
				boolean completed = ensureRegistrationCompleted(candidate, storeId, false);
				candidateRepository.save(candidate);
				return completed && decision.plan() == Plan.DEDUP ? Outcome.DEDUP_RESOLVED : Outcome.RECOVERED;
			}
			case INSERT -> {
				return insertNew(servingWriter, item, candidate, decision.evidence());
			}
			default -> throw new IllegalStateException("처리하지 않은 판정: " + decision.plan());
		}
	}

	private Outcome insertNew(ServingWriter servingWriter, CandidateRegisterRequest.Item item,
			NewStoreCandidate candidate, LocationEvidence evidence) {
		// 운영 쓰기 전에 operation 을 발급하고 즉시 확정한다 — 응답을 잃어도 시도 사실이 남는다
		UUID operationId = UUID.randomUUID();
		candidate.prepareRegistration(operationId);
		candidateRepository.saveAndFlush(candidate);

		Long storeId;
		try {
			storeId = servingWriter.insertStore(item.name(), item.address(),
				item.longitude(), item.latitude(), item.phone(),
				evidence.region1st(), evidence.region2nd(), evidence.region3rd(), candidate.getNaverPlaceId());
		} catch (RuntimeException e) {
			// INSERT 가 들어갔는지 알 수 없다 — MATCHED 로 되돌리면 중복 INSERT 를 시도한다
			candidate.markRegistrationUnknown("insertStore 실패: " + e.getMessage());
			candidateRepository.save(candidate);
			log.error("등록 결과 불명 — mgmtNo {} operation {}: {}", item.mgmtNo(), operationId, e.toString());
			return Outcome.UNKNOWN;
		}
		candidate.markStorePresent(storeId, true);
		candidateRepository.saveAndFlush(candidate);

		// 어떤 근거로 지역을 썼는지 남긴다 — 승인 경로(ProposalCommandService.withEvidence)와 같은 키
		Map<String, Object> added = new LinkedHashMap<>();
		added.put("name", item.name());
		added.put("address", item.address());
		added.put("lon", item.longitude());
		added.put("lat", item.latitude());
		added.put("naverPlaceId", candidate.getNaverPlaceId());
		added.put("mgmtNo", candidate.getMgmtNo());
		added.put("operationId", operationId.toString());
		added.put("region_1st", evidence.region1st());
		added.put("region_2nd", evidence.region2nd());
		added.put("region_3rd", evidence.region3rd());
		added.put("bjd_code", evidence.bjdCode());
		added.put("region_resolution_id", evidence.resolutionId());
		added.put("region_reference_version", evidence.referenceVersion());
		applyLogCommandService.recordBeforeApply(storeId, "store.add", null,
			objectMapper.writeValueAsString(added), ApplyOrigin.PROPOSAL, null, null);

		ensureRegistrationCompleted(candidate, storeId, true);
		candidateRepository.save(candidate);
		return Outcome.REGISTERED;
	}

	/**
	 * 등록 미리보기 — 등록 배치와 같은 판정을 운영·로컬 쓰기 없이 돌려준다.
	 *
	 * @param probe true 면 INSERT 판정 항목마다 운영에 같은 INSERT 를 실행하고 롤백한다
	 *              ({@link ServingWriter#probeInsertStore}). 운영 제약 통과를 확인하는 dry-run 이며,
	 *              IDENTITY 시퀀스만 한 칸씩 넘어간다
	 */
	public List<CandidateRegisterPreview> preview(CandidateRegisterRequest request, boolean probe) {
		ServingReader servingReader = servingReaderProvider.getIfAvailable();
		ServingWriter servingWriter = probe ? servingWriterProvider.getIfAvailable() : null;
		if (servingReader == null || (probe && servingWriter == null)) {
			throw new ServingException(ServingErrorCode.PROD_DB_NOT_CONFIGURED);
		}
		List<CandidateRegisterPreview> previews = new ArrayList<>();
		for (CandidateRegisterRequest.Item item : request.candidates()) {
			try {
				Decision decision = decide(servingReader, item);
				Long probeRowId = null;
				String probeError = null;
				if (probe && decision.plan() == Plan.INSERT) {
					LocationEvidence evidence = decision.evidence();
					try {
						probeRowId = servingWriter.probeInsertStore(item.name(), item.address(),
							item.longitude(), item.latitude(), item.phone(), evidence.region1st(),
							evidence.region2nd(), evidence.region3rd(), decision.candidate().getNaverPlaceId());
					} catch (RuntimeException e) {
						probeError = truncate(e.getMessage());
					}
				}
				previews.add(new CandidateRegisterPreview(item.mgmtNo(), item.name(), item.address(),
					decision.plan().name(), decision.existing() == null ? null : decision.existing().id(),
					decision.reason(), decision.evidence(), probeRowId, probeError));
			} catch (RuntimeException e) {
				previews.add(new CandidateRegisterPreview(item.mgmtNo(), item.name(), item.address(),
					"ERROR", null, truncate(e.getMessage()), null, null, null));
			}
		}
		return previews;
	}

	private static String truncate(String text) {
		return text == null || text.length() <= 300 ? text : text.substring(0, 300);
	}

	/**
	 * 등록 후속의 공통 절차 (v3 P1-3) — 신규 INSERT 와 기존 가게 귀속이 같은 길을 지난다.
	 * 여기를 통과해야 REGISTERED·COMPLETED 로 닫히고, 하나라도 못 하면 NEEDS_RECOVERY 로 남아 재실행이 보완한다.
	 *
	 * @param createdHere 이 배치가 store 를 만들었는지 — 기존 가게에는 생성 provenance 를 지어내지 않는다
	 * @return 후속까지 끝났는지
	 */
	private boolean ensureRegistrationCompleted(NewStoreCandidate candidate, long storeId, boolean createdHere) {
		try {
			if (createdHere) {
				provenanceRepository.save(StoreFieldProvenance.builder()
					.storeId(storeId).field("store.add").source(ProvenanceSource.LOCALDATA).build());
			}
			// 초기 후속 수집의 예약은 sync_state 의 due 다 — 사람의 추가 호출에 의존하지 않는다
			StoreSyncState syncState = storeSyncStateRepository
				.findByStoreIdAndSource(storeId, SyncSource.NAVER)
				.orElse(null);
			if (syncState == null) {
				storeSyncStateRepository.save(StoreSyncState.builder()
					.storeId(storeId)
					.source(SyncSource.NAVER)
					.externalId(candidate.getNaverPlaceId())
					.tier(SyncTier.T1)
					.build());
			} else if (syncState.ensureLinked(candidate.getNaverPlaceId())) {
				log.info("등록 후속 보완 — store {} sync_state 외부 ID·due 채움", storeId);
			}
			candidate.completeRegistration();
			return true;
		} catch (RuntimeException e) {
			// 운영에 가게는 있다 — 그 사실을 지우지 않고, 후속이 빠졌다는 것만 남긴다
			candidate.markNeedsRecovery("후속 보완 실패: " + e.getMessage());
			log.warn("등록 후속 누락 — store {} mgmtNo {}: {}", storeId, candidate.getMgmtNo(), e.getMessage());
			return false;
		}
	}

	/**
	 * DELETED 가게의 재활성 — 이전 제외 사유를 사람이 검토한 뒤에만 부르는 별도 경로 (v3 P1-3).
	 * 등록 배치는 이 일을 하지 않는다: 같은 이유로 다시 지워질 가게를 자동으로 되살리지 않기 위해서다.
	 *
	 * @param reviewNote 검토 결과 — 왜 되살려도 되는지. 비어 있으면 진행하지 않는다
	 */
	public boolean reactivateAfterReview(String mgmtNo, String reviewNote) {
		if (reviewNote == null || reviewNote.isBlank()) {
			throw new IllegalArgumentException("이전 제외 사유 검토 결과가 필요합니다");
		}
		ServingReader servingReader = servingReaderProvider.getIfAvailable();
		ServingWriter servingWriter = servingWriterProvider.getIfAvailable();
		if (servingReader == null || servingWriter == null) {
			throw new ServingException(ServingErrorCode.PROD_DB_NOT_CONFIGURED);
		}
		NewStoreCandidate candidate = candidateRepository.findByMgmtNo(mgmtNo).orElseThrow();
		ServingReader.StoreRef ref = servingReader.findStoreByNaverPlaceId(candidate.getNaverPlaceId())
			.orElseThrow();
		if (!"DELETED".equals(ref.status())) {
			return false;
		}
		applyLogCommandService.recordBeforeApply(ref.id(), "store.status",
			objectMapper.writeValueAsString("DELETED"), objectMapper.writeValueAsString("ACTIVE"),
			ApplyOrigin.PROPOSAL, null, null);
		if (servingWriter.reactivateStore(ref.id()) == 0) {
			candidate.markRegistrationUnknown("reactivateStore 0행 — 그새 상태가 바뀌었습니다");
			candidateRepository.save(candidate);
			return false;
		}
		provenanceRepository.save(StoreFieldProvenance.builder()
			.storeId(ref.id()).field("store.status").source(ProvenanceSource.LOCALDATA).build());
		candidate.markStorePresent(ref.id(), false);
		ensureRegistrationCompleted(candidate, ref.id(), false);
		candidate.hold(candidate.getRegistrationState(), "재활성 검토: " + reviewNote);
		candidateRepository.save(candidate);
		log.info("DELETED 가게 재활성 — store {} mgmtNo {} 검토: {}", ref.id(), mgmtNo, reviewNote);
		return true;
	}
}
