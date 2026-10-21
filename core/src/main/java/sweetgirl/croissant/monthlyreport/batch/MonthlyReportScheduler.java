package sweetgirl.croissant.monthlyreport.batch;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportBatchService;

@Slf4j
@Component
@RequiredArgsConstructor
public class MonthlyReportScheduler {
	private final MonthlyReportBatchService batch;

	/** 시각은 정책 확정 후 설정한다. 기본 '-'는 Spring의 스케줄 비활성 값이다. */
	@Scheduled(cron = "${monthly-report.batch.cron:-}", zone = "Asia/Seoul")
	@SchedulerLock(name = "MonthlyReportScheduler_runDue", lockAtMostFor = "PT30M")
	public void runDue() {
		try {
			log.info("[월간 보고서] 작업 처리 완료 result={}", batch.runDue());
		} catch (RuntimeException e) {
			log.error("[월간 보고서] 작업 조회 실패 — 다음 실행에서 재시도", e);
		}
	}
}
