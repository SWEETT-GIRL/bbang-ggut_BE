package sweetgirl.croissant.monthlyreport.service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.YearMonth;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.global.exception.CustomException;
import sweetgirl.croissant.monthlyreport.code.MonthlyReportErrorCode;
import sweetgirl.croissant.monthlyreport.config.MonthlyReportBatchProperties;
import sweetgirl.croissant.monthlyreport.repository.MonthlyReportJobRepository;
import sweetgirl.croissant.monthlyreport.repository.MonthlyReportSourceHistoryRepository;

/** 공통 적재와 회원별 발행은 각각 커밋한다. 한 회원의 실패가 다른 회원을 되돌리지 않는다. */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class MonthlyReportBatchService {
	private final MonthlyReportJobRepository jobs;
	private final MonthlyReportSourceHistoryRepository history;
	private final MonthlyReportIngestRecoveryService recovery;
	private final MonthlyReportJobWorker worker;
	private final MonthlyReportBatchProperties properties;

	public Result runDue() {
		Instant scanAt = Instant.now();
		int published = 0;
		int failed = 0;
		int skipped = 0;
		int blockedMonths = 0;
		for (String month : jobs.findDueMonths(scanAt, properties.getMaxAttempts())) {
			try {
				LocalDateTime coveredFrom = history.findCoveredFrom();
				if (coveredFrom == null || !coveredFrom.isBefore(
					YearMonth.parse(month).plusMonths(1).atDay(1).atStartOfDay())) {
					throw new CustomException(MonthlyReportErrorCode.SOURCE_HISTORY_UNAVAILABLE);
				}
				recovery.ensureComplete(month);
			} catch (RuntimeException e) {
				blockedMonths++;
				log.warn("[월간 보고서] 원천 준비 대기 month={} cause={}", month, e.getClass().getSimpleName());
				continue;
			}
			long afterId = 0;
			Slice<Long> page;
			do {
				page = jobs.findDueMembers(month, afterId, scanAt, properties.getMaxAttempts(),
					PageRequest.of(0, properties.getPageSize()));
				for (Long memberId : page.getContent()) {
					try {
						switch (worker.run(memberId, month)) {
							case PUBLISHED -> published++;
							case FAILED -> failed++;
							case SKIPPED -> skipped++;
						}
					} catch (RuntimeException e) {
						failed++;
						log.error("[월간 보고서] 작업 상태 저장 실패 memberId={} month={}", memberId, month, e);
					}
					afterId = memberId;
				}
			} while (page.hasNext());
		}
		return new Result(published, failed, skipped, blockedMonths);
	}

	public record Result(int published, int failed, int skipped, int blockedMonths) {
	}
}
