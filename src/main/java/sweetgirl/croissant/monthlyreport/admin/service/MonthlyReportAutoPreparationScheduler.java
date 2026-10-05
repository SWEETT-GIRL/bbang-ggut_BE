package sweetgirl.croissant.monthlyreport.admin.service;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.monthlyreport.config.MonthlyReportAutoPreparationProperties;

/** 전월 초안을 자동 등록한다. 발행과 푸시는 어드민 승인 경로로만 진행한다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class MonthlyReportAutoPreparationScheduler {
	private final MonthlyReportAutoPreparationProperties properties;
	private final MonthlyReportAdminService reports;

	// 매월 1일 00:05 KST부터 전월을 등록한다. 중단 중 놓친 실행은 재시작/다음 시간에 회복한다.
	@EventListener(ApplicationReadyEvent.class)
	@Scheduled(cron = "${monthly-report.auto-preparation.cron:0 5 * * * *}", zone = "Asia/Seoul")
	public void prepare() {
		if (!properties.isEnabled()) {
			return;
		}
		try {
			reports.prepareAutomatically(previousMonth(Instant.now()), properties.getAssetVersion());
		} catch (RuntimeException error) {
			log.error("월간 보고서 자동 생성 등록 실패", error);
		}
	}

	public static String previousMonth(Instant now) {
		return YearMonth.from(now.atZone(ZoneId.of("Asia/Seoul"))).minusMonths(1).toString();
	}
}
