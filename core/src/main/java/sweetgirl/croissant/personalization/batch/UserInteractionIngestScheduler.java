package sweetgirl.croissant.personalization.batch;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.personalization.service.UserInteractionIngestCommandService;
import sweetgirl.croissant.personalization.service.UserTasteProfileCommandService;

/**
 * 매일 04:00 전날 신호를 옮겨 적고, 이어서 프로필을 다시 계산한다 (#663). 둘은 따로 try/catch 다 — 적재가 실패해도 재계산은
 * 어제까지의 신호로 돈다. 실패 날짜는 월간 보고서의 누락 복구 또는 관리자 수동 적재로 보완한다(유니크 제약으로 중복 없음).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserInteractionIngestScheduler {

	private final UserInteractionIngestCommandService ingestCommandService;
	private final UserTasteProfileCommandService profileCommandService;

	@Scheduled(cron = "0 0 4 * * *", zone = UserInteractionIngestCommandService.ZONE)
	@SchedulerLock(name = "UserInteractionIngestScheduler_dailyIngestAndRecompute", lockAtMostFor = "PT30M")
	public void dailyIngestAndRecompute() {
		log.info("[개인화] 전날 신호 적재 시작");
		try {
			ingestCommandService.ingestYesterday();
		} catch (RuntimeException e) {
			log.error("[개인화] 전날 신호 적재 실패 — 누락 날짜 복구 또는 관리자 수동 적재 필요", e);
		}
		log.info("[개인화] 프로필 재계산 시작");
		try {
			profileCommandService.recomputeAll();
		} catch (RuntimeException e) {
			log.error("[개인화] 프로필 재계산 실패 — 다음 주기에 재시도, 필요하면 관리자 수동 실행", e);
		}
	}
}
