package sweetgirl.croissant.store.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.store.code.StoreErrorCode;
import sweetgirl.croissant.store.exception.StoreException;
import sweetgirl.croissant.store.repository.StorePopularityRepository;

/** 가게 인기도(store.popularity_score) 갱신. 주간 배치와 어드민 수동 갱신이 같은 경로를 탄다. */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class StorePopularityCommandService {

	private final StorePopularityRepository storePopularityRepository;

	// pg advisory lock 키 — 이 갱신 작업 전용 임의 상수. 다른 advisory lock 과 겹치지 않게 고정한다.
	static final long POPULARITY_REFRESH_LOCK_KEY = 20260818_1L;

	/**
	 * @return 갱신된 가게 수(값이 실제로 바뀐 행)
	 * @throws StoreException 다른 갱신이 이미 실행 중이면 POPULARITY_REFRESH_ALREADY_RUNNING(409)
	 */
	public int refreshAllPopularityScores() {
		if (!storePopularityRepository.tryLockPopularityRefresh(POPULARITY_REFRESH_LOCK_KEY)) {
			throw new StoreException(StoreErrorCode.POPULARITY_REFRESH_ALREADY_RUNNING);
		}
		long startedAt = System.currentTimeMillis();
		int updated = storePopularityRepository.refreshAllPopularityScores();
		log.info("[인기도] store.popularity_score 갱신 완료 updated={} elapsedMs={}", updated,
			System.currentTimeMillis() - startedAt);
		return updated;
	}
}
