package sweetgirl.croissant.store.batch;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.store.config.StoreTrendingProperties;
import sweetgirl.croissant.store.service.StoreTrendingBaselineCommandService;
import sweetgirl.croissant.store.service.trending.TrendingRankingService;

/**
 * 급부상 배치 두 개.
 * <ul>
 * <li>평소 수준 — 매일 05:00. 취향 신호 적재(04:00, 전날분)가 끝난 뒤에 돈다.</li>
 * <li>순위표 — 5분마다. 실시간 신호(Redis)로 다시 계산한다.</li>
 * </ul>
 * 실패해도 다음 주기에 다시 돌고, 그 사이에는 직전 값으로 노출한다(정확성보다 가용성).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StoreTrendingScheduler {

	private final StoreTrendingBaselineCommandService baselineCommandService;
	private final TrendingRankingService rankingService;
	private final StoreTrendingProperties properties;

	@Scheduled(cron = "0 0 5 * * *", zone = "Asia/Seoul")
	@SchedulerLock(name = "StoreTrendingScheduler_dailyBaselineRefresh", lockAtMostFor = "PT10M")
	public void dailyBaselineRefresh() {
		if (!properties.isEnabled()) {
			return;
		}
		try {
			baselineCommandService.refreshBaselines(LocalDate.now());
		} catch (RuntimeException exception) {
			log.error("[급부상] 평소 수준 배치 실패 — 다음 날 재시도", exception);
		}
	}

	@Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT1M")
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
