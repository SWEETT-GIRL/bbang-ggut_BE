package sweetgirl.croissant.reward.batch;

import java.time.LocalDate;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.reward.service.CrumbGrantService;
import sweetgirl.croissant.reward.service.RankingRewardService;

/**
 * 월요일 새벽 — 지난주 전국 주간 순위 1~10 위에게 보상 줄을 만든다.
 *
 * <p>순위 스냅샷(00:05) 뒤에 돈다. 스냅샷이 늦어 못 만들었을 때를 위해 두 번 더 돈다 —
 * 줄은 (주, 회원) 유니크라 몇 번을 돌아도, 인스턴스가 둘이어도 한 줄이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RankingRewardScheduler {

	private final RankingRewardService rankingRewardService;

	@Scheduled(cron = "0 15,45 0 * * MON", zone = "Asia/Seoul")
	@Scheduled(cron = "0 30 6 * * MON", zone = "Asia/Seoul")
	public void issueLastWeek() {
		try {
			rankingRewardService.issueFor(LocalDate.now(CrumbGrantService.ZONE));
		} catch (RuntimeException exception) {
			log.error("[랭킹 주간 보상] 만들기 실패 — 다음 실행에서 다시 한다", exception);
		}
	}
}
