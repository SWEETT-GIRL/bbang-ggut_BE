package sweetgirl.croissant.alarm.batch;

import java.time.LocalDateTime;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.alarm.service.PushBatchService;

/** 모아 둔 푸시 묶음을 매분 내보낸다. 서버가 둘이어도 한쪽만 돈다(ShedLock). */
@Slf4j
@Component
@RequiredArgsConstructor
public class PushBatchScheduler {

	private final PushBatchService pushBatchService;

	@Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
	@SchedulerLock(name = "PushBatchScheduler_flushDue", lockAtMostFor = "PT5M")
	public void flushDue() {
		try {
			pushBatchService.flushDue(LocalDateTime.now());
		} catch (RuntimeException exception) {
			log.error("[푸시 묶음] 내보내기 실패 — 다음 분에 다시 본다", exception);
		}
	}
}
