package sweetgirl.croissant.store.batch;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.store.service.StorePopularityCommandService;

/**
 * 주간 인기도 갱신 — 검색 랭킹·인기순 정렬이 쓰는 store.popularity_score 를 다시 계산한다.
 * 실패해도 다음 주에 다시 돌고, 그 사이에는 직전 값으로 랭킹한다(검색 정확성보다 가용성 우선).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StorePopularityScoreScheduler {

	private final StorePopularityCommandService storePopularityCommandService;

	@Scheduled(cron = "0 0 3 * * MON", zone = "Asia/Seoul")
	@SchedulerLock(name = "StorePopularityScoreScheduler_weeklyPopularityRefresh", lockAtMostFor = "PT30M")
	public void weeklyPopularityRefresh() {
		log.info("[인기도] 주간 popularity_score 배치 시작");
		try {
			storePopularityCommandService.refreshAllPopularityScores();
		} catch (RuntimeException e) {
			log.error("[인기도] 주간 popularity_score 배치 실패 — 다음 주기에 재시도", e);
		}
	}
}
