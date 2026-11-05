package sweetgirl.croissant.store.batch;

import java.time.LocalDateTime;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.store.config.StoreTrendingProperties;
import sweetgirl.croissant.store.service.trending.TrendingRankingService;

/**
 * 급부상 순위표 — 주기마다. 실시간 신호(Redis)로 다시 계산한다.
 * 하루 한 번 도는 평소 수준은 배치 서버의 {@code StoreTrendingBaselineScheduler} 가 맡는다.
 * 실패해도 다음 주기에 다시 돌고, 그 사이에는 직전 값으로 노출한다(정확성보다 가용성).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StoreTrendingScheduler {

	private final TrendingRankingService rankingService;
	private final StoreTrendingProperties properties;

	@Scheduled(fixedDelayString = "#{@storeTrendingProperties.refreshInterval}", initialDelayString = "PT1M")
	@SchedulerLock(name = "StoreTrendingScheduler_refreshRanking", lockAtMostFor = "PT4M")
	public void refreshRanking() {
		if (!properties.isEnabled()) {
			return;
		}
		try {
			rankingService.refresh(LocalDateTime.now());
		} catch (RuntimeException exception) {
			log.warn("[급부상] 순위표 갱신 실패 — 다음 주기에 재시도 cause={}", exception.toString());
		}
	}
}
