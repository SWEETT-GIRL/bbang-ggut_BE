package sweetgirl.croissant.searchmiss.service;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.searchmiss.repository.SearchMissRepository;
import sweetgirl.croissant.store.entity.SearchKeywordCorrection;
import sweetgirl.croissant.store.repository.SearchKeywordCorrectionRepository;
import sweetgirl.croissant.store.service.search.SearchKeywordCorrector;
import sweetgirl.croissant.store.service.search.SearchTextNormalizer;

/**
 * 판정 중 쓰기. 판정 전체를 한 트랜잭션으로 묶지 않는다 — 네이버·LLM 호출 동안 커넥션을 쥐게 되고, 교정을 넣은 뒤
 * "다시 검색하면 나오나"를 확인하려면 교정이 먼저 커밋돼 있어야 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchMissTriageCommandService {

	private final SearchMissRepository searchMissRepository;
	private final SearchKeywordCorrectionRepository searchKeywordCorrectionRepository;
	private final SearchKeywordCorrector searchKeywordCorrector;

	/**
	 * 검색어 교정을 넣는다. 같은 검색어(정규화 기준) 교정이 이미 있으면 넣지 않는다.
	 *
	 * <p>넣고 나면 교정 캐시를 비운다 — 바로 뒤의 재검색 확인이 새 교정을 타야 한다. 캐시는 다음 접근에서 다시 읽으므로
	 * 이 트랜잭션이 커밋된 뒤에 읽힌다.
	 *
	 * @return 새로 넣은 교정 id. 이미 있었으면 비어 있다
	 */
	@Transactional
	public Optional<Long> addCorrection(String keyword, String correctedQuery, Long searchMissId) {
		String normalizedKeyword = SearchTextNormalizer.normalize(keyword);
		if (searchKeywordCorrectionRepository.existsByNormalizedKeyword(normalizedKeyword)) {
			return Optional.empty();
		}
		SearchKeywordCorrection saved = searchKeywordCorrectionRepository.save(
			SearchKeywordCorrection.fromSearchMiss(normalizedKeyword, keyword.trim(), correctedQuery, searchMissId));
		searchKeywordCorrector.invalidate();
		return Optional.of(saved.getId());
	}

	/** 재검색 확인에 실패한 교정을 되돌린다. */
	@Transactional
	public void removeCorrection(Long correctionId) {
		searchKeywordCorrectionRepository.deleteById(correctionId);
		searchKeywordCorrector.invalidate();
	}

	/**
	 * 판정을 기록한다. 이 판정이 자동 적용 대상이고 사람이 볼 필요가 없으면 처리 상태도 바꾼다.
	 *
	 * <p>판정 도중 행이 사라졌으면(관리자 삭제) 남길 곳이 없으니 넘어간다.
	 */
	@Transactional
	public void recordDecision(Long searchMissId, TriageDecision decision, boolean apply,
			LocalDateTime triagedAt) {
		searchMissRepository.findById(searchMissId).ifPresentOrElse(searchMiss -> {
			searchMiss.recordTriage(decision.verdict(), decision.matchedStoreId(), decision.evidence(), triagedAt);
			if (apply && decision.resolvedStatus() != null) {
				searchMiss.handle(decision.resolvedStatus(), triagedAt);
			}
		}, () -> log.info("[검색어 판정] 판정 중 행이 사라짐 id={}", searchMissId));
	}

	/**
	 * 처리 완료 후 보관 기간이 지난 행을 지운다. PENDING 은 {@code handled_at}이 없어 대상이 아니다.
	 *
	 * @return 지운 행 수
	 */
	@Transactional
	public long deleteHandledBefore(LocalDateTime threshold) {
		return searchMissRepository.deleteByHandledAtBefore(threshold);
	}
}
