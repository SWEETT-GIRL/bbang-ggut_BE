package sweetgirl.croissant.store.batch;

import java.time.LocalDate;

import org.springframework.stereotype.Component;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.batch.job.BatchJob;
import sweetgirl.croissant.store.config.StoreTrendingProperties;
import sweetgirl.croissant.store.service.StoreTrendingBaselineCommandService;

/**
 * 급부상 평소 수준 — 매일 05:00. 취향 신호 적재(04:00, 전날분)가 끝난 뒤에 돈다.
 * 5분마다 도는 순위표는 API 서버의 {@code StoreTrendingScheduler} 가 맡는다.
 * 실패해도 다음 날 다시 돌고, 그 사이에는 직전 값으로 노출한다(정확성보다 가용성).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StoreTrendingBaselineScheduler {

	private final StoreTrendingBaselineCommandService baselineCommandService;
	private final StoreTrendingProperties properties;

	@BatchJob("store-trending-baseline")
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
}
