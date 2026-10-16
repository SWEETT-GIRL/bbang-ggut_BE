package sweetgirl.croissant.ranking.batch;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.ranking.service.RankingSnapshotCommandService;

/**
 * 매일 00:05 KST — 어제 마감 순위를 남긴다. 실패하면 그날 변동(▲▼NEW)만 안 보이고 다음 날 다시 돈다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RankingSnapshotScheduler {

	private final RankingSnapshotCommandService snapshotCommandService;

	@Scheduled(cron = "0 5 0 * * *", zone = "Asia/Seoul")
	@SchedulerLock(name = "RankingSnapshotScheduler_dailySnapshot", lockAtMostFor = "PT10M")
	public void dailySnapshot() {
		log.info("[랭킹 스냅샷] 일별 배치 시작");
		try {
			int saved = snapshotCommandService.snapshotYesterday();
			log.info("[랭킹 스냅샷] 일별 배치 끝 saved={}", saved);
		} catch (RuntimeException exception) {
			log.error("[랭킹 스냅샷] 일별 배치 실패 — 내일 다시 돈다", exception);
		}
	}
}
