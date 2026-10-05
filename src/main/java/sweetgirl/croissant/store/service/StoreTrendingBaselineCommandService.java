package sweetgirl.croissant.store.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.store.code.StoreErrorCode;
import sweetgirl.croissant.store.config.StoreTrendingProperties;
import sweetgirl.croissant.store.enums.TrendingSignal;
import sweetgirl.croissant.store.event.StoreTrendingBaselineRefreshedEvent;
import sweetgirl.croissant.store.exception.StoreException;
import sweetgirl.croissant.store.repository.trending.StoreTrendingRepository;

/**
 * 가게 평소 수준(store.trending_baseline) 갱신. 매일 배치와 어드민 수동 갱신이 같은 경로를 탄다.
 *
 * <p>기간은 최근 창(7일) <b>바로 앞</b> 28일이다 — 최근 활동이 평소 수준에 섞이면 급부상이 스스로를 깎는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class StoreTrendingBaselineCommandService {

	// pg advisory lock 키 — 이 갱신 작업 전용 상수(인기도 갱신 20260818_1 과 겹치지 않게)
	static final long BASELINE_REFRESH_LOCK_KEY = 20261005_1L;

	private final StoreTrendingRepository storeTrendingRepository;
	private final StoreTrendingProperties properties;
	private final ApplicationEventPublisher eventPublisher;

	/**
	 * @param today 기준일 — 이날 0시에서 최근 창만큼 뺀 시각까지가 평소 기간의 끝이다
	 * @return 값이 바뀐 가게 수
	 * @throws StoreException 다른 갱신이 실행 중이면 TRENDING_BASELINE_REFRESH_ALREADY_RUNNING(409)
	 */
	public int refreshBaselines(LocalDate today) {
		if (!storeTrendingRepository.tryLock(BASELINE_REFRESH_LOCK_KEY)) {
			throw new StoreException(StoreErrorCode.TRENDING_BASELINE_REFRESH_ALREADY_RUNNING);
		}
		LocalDateTime to = today.atStartOfDay().minus(properties.getWindow());
		LocalDateTime from = to.minus(properties.getBaselineWindow());
		long startedAt = System.currentTimeMillis();
		int updated = storeTrendingRepository.refreshBaselines(interactionWeights(), from, to,
			properties.getBaselineWindow().toDays());
		log.info("[급부상] 평소 수준 갱신 완료 기간=[{}, {}) updated={} elapsedMs={}", from, to, updated,
			System.currentTimeMillis() - startedAt);
		// 자동완성 캐시는 평소 수준 순으로 정렬된 결과를 담고 있다 — 커밋 뒤에 비운다
		eventPublisher.publishEvent(new StoreTrendingBaselineRefreshedEvent(updated));
		return updated;
	}

	private Map<String, Double> interactionWeights() {
		Map<String, Double> weights = new LinkedHashMap<>();
		for (TrendingSignal signal : TrendingSignal.values()) {
			double weight = properties.weightOf(signal);
			if (weight > 0) {
				weights.put(signal.interactionType(), weight);
			}
		}
		return weights;
	}
}
