package sweetgirl.croissant.abuse.batch;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.abuse.entity.AbuseReport;
import sweetgirl.croissant.abuse.enums.AbuseReportStatus;
import sweetgirl.croissant.abuse.repository.AbuseReportRepository;
import sweetgirl.croissant.notification.service.AdminReportNotificationPublisher;

/** 24시간 안에 처리 안 된 신고를 슬랙에 한 번 더 알린다(건당 한 번). 애플 1.2 "24시간 안 조치"의 안전장치. */
@Component
@RequiredArgsConstructor
public class AbuseReportReminderScheduler {

	static final int OVERDUE_HOURS = 24;

	private final AbuseReportRepository abuseReportRepository;
	private final AdminReportNotificationPublisher adminReportNotificationPublisher;

	@Scheduled(cron = "${abuse.reminder.cron:0 10 * * * *}", zone = "Asia/Seoul")
	@SchedulerLock(name = "AbuseReportReminderScheduler_remindOverdue", lockAtMostFor = "PT10M")
	@Transactional
	public int remindOverdue() {
		LocalDateTime now = LocalDateTime.now();
		List<AbuseReport> overdue = abuseReportRepository.findOverdue(
			AbuseReportStatus.PENDING, now.minusHours(OVERDUE_HOURS));
		if (overdue.isEmpty()) {
			return 0;
		}
		adminReportNotificationPublisher.publishAbuseReportsOverdue(overdue);
		overdue.forEach(report -> report.markReminded(now));
		return overdue.size();
	}
}
