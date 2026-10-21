package sweetgirl.croissant.monthlyreport.admin.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.monthlyreport.admin.entity.MonthlyReportRun.State;
import sweetgirl.croissant.monthlyreport.admin.repository.MonthlyReportPushRepository;
import sweetgirl.croissant.monthlyreport.admin.repository.MonthlyReportRunRepository;

/** 등록된 작업을 처리한다. 자동 생성 작업도 검토 완료 후 어드민이 승인해야 발행한다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class MonthlyReportAdminScheduler {

	private static final String RUNS_LOCK = "MonthlyReportAdminScheduler_processActiveReportRuns";
	private static final String PUSHES_LOCK = "MonthlyReportAdminScheduler_sendScheduledReportPushes";

	private final MonthlyReportRunRepository runs;
	private final MonthlyReportRunWorker worker;
	private final MonthlyReportPushRepository pushes;
	private final MonthlyReportPushWorker pushWorker;

	// 5초 폴링이라 최소 점유는 짧게 — 기본 30초면 폴링이 30초로 느려진다
	@Scheduled(fixedDelayString = "${monthly-report.admin.poll-delay-ms:5000}")
	@SchedulerLock(name = RUNS_LOCK, lockAtMostFor = "PT5M", lockAtLeastFor = "PT3S")
	public void processActiveReportRuns() {
		for (UUID id : runs.findActive(List.of(State.PREPARING, State.PUBLISHING), PageRequest.of(0, 20))) {
			for (int i = 0; i < 20; i++) {
				try {
					worker.processReportRunStep(id);
				} catch (RuntimeException e) {
					log.error("월간 어드민 작업 처리 실패 runId={}", id, e);
					break;
				}
			}
		}
	}

	@Scheduled(fixedDelayString = "${monthly-report.admin.push-delay-ms:10000}")
	@SchedulerLock(name = PUSHES_LOCK, lockAtMostFor = "PT5M", lockAtLeastFor = "PT5S")
	public void sendScheduledReportPushes() {
		for (Long id : pushes.findDue(Instant.now(), PageRequest.of(0, 100))) {
			try {
				pushWorker.sendScheduledReportPush(id);
			} catch (RuntimeException e) {
				log.error("월간 푸시 예약 처리 실패 notificationId={}", id, e);
			}
		}
	}
}
