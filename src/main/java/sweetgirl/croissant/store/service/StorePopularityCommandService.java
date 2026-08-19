package sweetgirl.croissant.store.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.store.repository.StorePopularityRepository;

/** 가게 인기도(store.popularity_score) 갱신. 주간 배치와 어드민 수동 갱신이 같은 경로를 탄다. */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class StorePopularityCommandService {

	private final StorePopularityRepository storePopularityRepository;

	/** @return 갱신된 가게 수 */
	public int refreshAllPopularityScores() {
		long startedAt = System.currentTimeMillis();
		int updated = storePopularityRepository.refreshAllPopularityScores();
		log.info("[인기도] store.popularity_score 갱신 완료 updated={} elapsedMs={}", updated,
			System.currentTimeMillis() - startedAt);
		return updated;
	}
}
