package sweetgirl.croissant.personalization.batch;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.personalization.service.UserInteractionIngestCommandService;

/**
 * 매일 04:00 전날 신호를 옮겨 적는다 (#663). 실패해도 다음 날 다시 돌고, 같은 범위를 관리자가 수동으로 다시 넣을 수 있다
 * (유니크 제약으로 중복 없음). 프로필 재계산은 2단계에서 이 뒤에 잇는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserInteractionIngestScheduler {

	private final UserInteractionIngestCommandService ingestCommandService;

	@Scheduled(cron = "0 0 4 * * *", zone = "Asia/Seoul")
	public void dailyIngest() {
		log.info("[개인화] 전날 신호 적재 시작");
		try {
			ingestCommandService.ingestYesterday();
		} catch (RuntimeException e) {
			log.error("[개인화] 전날 신호 적재 실패 — 다음 주기에 재시도, 필요하면 관리자 수동 실행", e);
		}
	}
}
