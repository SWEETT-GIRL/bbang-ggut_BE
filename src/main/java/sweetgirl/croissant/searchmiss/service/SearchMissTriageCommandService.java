package sweetgirl.croissant.searchmiss.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.searchmiss.repository.SearchMissRepository;
import sweetgirl.croissant.store.entity.StoreNameAlias;
import sweetgirl.croissant.store.event.StoreSearchNameChangedEvent;
import sweetgirl.croissant.store.repository.StoreNameAliasRepository;
import sweetgirl.croissant.store.repository.StoreRepository;

/**
 * 판정 중 쓰기. 판정 전체를 한 트랜잭션으로 묶지 않는다 — 네이버·LLM 호출 동안 커넥션을 쥐게 되고, 별칭을 넣은 뒤
 * "다시 검색하면 나오나"를 확인하려면 별칭이 먼저 커밋돼 있어야 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchMissTriageCommandService {

	private final SearchMissRepository searchMissRepository;
	private final StoreNameAliasRepository storeNameAliasRepository;
	private final StoreRepository storeRepository;
	private final ApplicationEventPublisher eventPublisher;

	/**
	 * 가게들에 별칭을 넣는다. 이미 있는 (가게, 별칭)은 건너뛴다.
	 *
	 * @return 새로 넣은 별칭 id — 검증에 실패하면 이것만 되돌린다(이미 있던 별칭은 건드리지 않는다)
	 */
	@Transactional
	public List<Long> addAliases(String alias, List<Long> storeIds) {
		List<Long> insertedIds = new ArrayList<>();
		for (Long storeId : storeIds) {
			if (storeNameAliasRepository.existsByStore_IdAndAlias(storeId, alias)) {
				continue;
			}
			StoreNameAlias saved = storeNameAliasRepository.save(
				StoreNameAlias.fromSearchMiss(storeRepository.getReferenceById(storeId), alias));
			insertedIds.add(saved.getId());
		}
		if (!insertedIds.isEmpty()) {
			// 자동완성 캐시에 "결과 없음"이 남아 있으면 별칭을 넣어도 TTL 동안 안 보인다. 커밋 뒤에 지운다
			eventPublisher.publishEvent(StoreSearchNameChangedEvent.of(alias));
		}
		return insertedIds;
	}

	@Transactional
	public void removeAliases(List<Long> aliasIds) {
		storeNameAliasRepository.deleteAllById(aliasIds);
	}

	/**
	 * 판정을 기록한다. auto-apply 이고 사람이 볼 필요가 없는 판정이면 처리 상태도 바꾼다.
	 *
	 * <p>판정 도중 행이 사라졌으면(관리자 삭제) 남길 곳이 없으니 넘어간다.
	 */
	@Transactional
	public void recordDecision(Long searchMissId, TriageDecision decision, boolean autoApply,
			LocalDateTime triagedAt) {
		searchMissRepository.findById(searchMissId).ifPresentOrElse(searchMiss -> {
			searchMiss.recordTriage(decision.verdict(), decision.matchedStoreId(), decision.evidence(), triagedAt);
			if (autoApply && decision.resolvedStatus() != null) {
				searchMiss.handle(decision.resolvedStatus(), triagedAt);
			}
		}, () -> log.info("[검색어 판정] 판정 중 행이 사라짐 id={}", searchMissId));
	}
}
