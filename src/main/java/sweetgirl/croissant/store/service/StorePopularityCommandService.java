package sweetgirl.croissant.store.service;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.store.code.StoreErrorCode;
import sweetgirl.croissant.store.config.StorePopularityProperties;
import sweetgirl.croissant.store.event.StorePopularityRefreshedEvent;
import sweetgirl.croissant.store.exception.StoreException;
import sweetgirl.croissant.store.repository.StorePopularityRepository;

/** 가게 인기도(store.popularity_score) 갱신. 주간 배치와 어드민 수동 갱신이 같은 경로를 탄다. */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class StorePopularityCommandService {

	private final StorePopularityRepository storePopularityRepository;
	private final StorePopularityProperties storePopularityProperties;
	private final ApplicationEventPublisher eventPublisher;

	// pg advisory lock 키 — 이 갱신 작업 전용 임의 상수. 다른 advisory lock 과 겹치지 않게 고정한다.
	static final long POPULARITY_REFRESH_LOCK_KEY = 20260818_1L;
	/** 추천 prior 상한(popularityCap)의 기준 분위 — 갱신 때마다 로그로 남겨 상한을 다시 볼 때를 알린다 */
	static final double CAP_REFERENCE_QUANTILE = 0.9;

	/**
	 * @return 갱신된 가게 수(값이 실제로 바뀐 행)
	 * @throws StoreException 다른 갱신이 이미 실행 중이면 POPULARITY_REFRESH_ALREADY_RUNNING(409)
	 */
	public int refreshAllPopularityScores() {
		if (!storePopularityRepository.tryLockPopularityRefresh(POPULARITY_REFRESH_LOCK_KEY)) {
			throw new StoreException(StoreErrorCode.POPULARITY_REFRESH_ALREADY_RUNNING);
		}
		long startedAt = System.currentTimeMillis();
		int updated = storePopularityRepository.refreshAllPopularityScores(
			storePopularityProperties.getConversionSmoothing(), storePopularityProperties.getRankingScaleQuantile());
		double referenceQuantile = storePopularityRepository.findActivePopularityQuantile(CAP_REFERENCE_QUANTILE);
		log.info("[인기도] store.popularity_score 갱신 완료 updated={} p90={} (recommendation popularityCap 기준) elapsedMs={}",
			updated, referenceQuantile, System.currentTimeMillis() - startedAt);
		// 자동완성 캐시는 인기도 순으로 정렬된 결과를 담고 있다 — 커밋 뒤에 통째로 비운다(월요일 새벽이라 다시 채우는 부하가 작다)
		eventPublisher.publishEvent(new StorePopularityRefreshedEvent(updated));
		return updated;
	}
}
