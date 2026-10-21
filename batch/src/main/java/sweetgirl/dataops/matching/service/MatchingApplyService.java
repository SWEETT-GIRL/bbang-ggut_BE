package sweetgirl.dataops.matching.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.dataops.matching.dto.request.MatchingProposalRequest;
import sweetgirl.dataops.matching.dto.response.MatchingApplyResult;
import sweetgirl.dataops.reconcile.entity.StoreFieldProvenance;
import sweetgirl.dataops.reconcile.enums.ProvenanceSource;
import sweetgirl.dataops.reconcile.repository.StoreFieldProvenanceRepository;
import sweetgirl.dataops.serving.client.ServingReader;
import sweetgirl.dataops.serving.client.ServingWriter;
import sweetgirl.dataops.serving.code.ServingErrorCode;
import sweetgirl.dataops.serving.dto.StoreCurrentValues;
import sweetgirl.dataops.serving.enums.ApplyOrigin;
import sweetgirl.dataops.serving.exception.ServingException;
import sweetgirl.dataops.serving.service.ApplyLogCommandService;
import tools.jackson.databind.ObjectMapper;

/**
 * 매칭 배치 반영 — 눈검사 게이트를 통과한 (storeId, placeId) 를 운영에 직접 반영한다.
 *
 * 매칭 옳고 그름의 "재판정"은 하지 않는다 (게이트에서 끝냄 = 사람 승인과 동치). 반영 단계가 하는 것은
 * 매칭 정오와 무관한 쓰기 안전장치뿐:
 *  ① 선점 확인 — 후보 확정 후 팀이 그새 매칭했으면 덮지 않고 건너뛴다 (반영 직전 재조회)
 *  ② apply_log 선기록 — 전량 롤백 근거
 *  ③ provenance 기록
 * 개별 실패는 격리(REQUIRES_NEW 선기록 + 자체 트랜잭션)해 배치 전체를 막지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MatchingApplyService {

	private final ObjectProvider<ServingReader> servingReaderProvider;
	private final ObjectProvider<ServingWriter> servingWriterProvider;
	private final ApplyLogCommandService applyLogCommandService;
	private final StoreFieldProvenanceRepository provenanceRepository;
	private final ObjectMapper objectMapper;

	public MatchingApplyResult applyBatch(MatchingProposalRequest request) {
		ServingReader servingReader = servingReaderProvider.getIfAvailable();
		ServingWriter servingWriter = servingWriterProvider.getIfAvailable();
		if (servingReader == null || servingWriter == null) {
			throw new ServingException(ServingErrorCode.PROD_DB_NOT_CONFIGURED);
		}

		int applied = 0;
		int skipped = 0;
		List<Long> failedStoreIds = new ArrayList<>();
		for (MatchingProposalRequest.Item item : request.candidates()) {
			try {
				if (applyOne(servingReader, servingWriter, item.storeId(), item.naverPlaceId())) {
					applied++;
				} else {
					skipped++;
				}
			} catch (RuntimeException e) {
				failedStoreIds.add(item.storeId());
				log.warn("매칭 반영 실패 — store {}: {}", item.storeId(), e.getMessage());
			}
		}
		log.info("매칭 배치 반영 — 요청 {}, 반영 {}, 선점 스킵 {}, 실패 {}",
			request.candidates().size(), applied, skipped, failedStoreIds.size());
		return new MatchingApplyResult(
			request.candidates().size(), applied, skipped, failedStoreIds.size(), failedStoreIds);
	}

	/**
	 * 반환 false = 선점(이미 매칭됨)으로 건너뜀.
	 * 트랜잭션을 묶지 않는다 — apply_log 는 REQUIRES_NEW 로 먼저 확정되고, place_id UPDATE 는 단일
	 * 문장(자동 커밋)이라 항목별로 독립 반영된다. 복구 근거는 apply_log. 배치 도중 실패가 다른 항목을
	 * 막지 않도록 호출자(applyBatch)가 항목별로 예외를 격리한다.
	 *
	 * 선점 방지는 UPDATE 의 조건(naver_place_id 가 여전히 NULL)이 담당한다 (v3 P1-1) — 사전 조회만으로는
	 * 읽고 쓰는 사이에 다른 경로가 매칭할 수 있다. 사전 조회는 불필요한 apply_log 를 줄이는 빠른 차단일 뿐이다.
	 */
	public boolean applyOne(ServingReader servingReader, ServingWriter servingWriter,
			long storeId, String naverPlaceId) {
		StoreCurrentValues current = servingReader.findStore(storeId).orElse(null);
		if (current == null || servingReader.hasNaverPlaceId(storeId)) {
			return false; // 그새 매칭됐거나 사라진 가게는 덮지 않는다
		}
		applyLogCommandService.recordBeforeApply(
			storeId, "store.naver_place_id", null, objectMapper.writeValueAsString(naverPlaceId),
			ApplyOrigin.PROPOSAL, null, null);
		if (servingWriter.updateStoreNaverPlaceId(storeId, null, naverPlaceId) == 0) {
			return false; // 조회와 쓰기 사이에 다른 경로가 매칭했다 — 덮지 않는다
		}
		provenanceRepository.save(StoreFieldProvenance.builder()
			.storeId(storeId).field("naver_place_id").source(ProvenanceSource.NAVER).build());
		return true;
	}
}
