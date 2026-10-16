package sweetgirl.croissant.admin.batch;

import java.time.Duration;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.admin.service.AdminAuditLogCommandService;

/** 매일 04:50 KST — 보관 기한(기본 180일)이 지난 어드민 감사 로그를 지운다. */
@Slf4j
@Component
public class AdminAuditLogRetentionScheduler {

	private final AdminAuditLogCommandService commandService;
	private final Duration retention;

	public AdminAuditLogRetentionScheduler(AdminAuditLogCommandService commandService,
			@Value("${admin.audit.retention-days:180}") int retentionDays) {
		this.commandService = commandService;
		this.retention = Duration.ofDays(retentionDays);
	}

	@Scheduled(cron = "${admin.audit.retention-cron:0 50 4 * * *}", zone = "Asia/Seoul")
	@SchedulerLock(name = "AdminAuditLogRetentionScheduler_deleteExpired", lockAtMostFor = "PT10M")
	public void deleteExpired() {
		try {
			int deleted = commandService.deleteCreatedBefore(Instant.now().minus(retention));
			log.info("[어드민 감사 로그] 보관 기한 지난 줄 삭제 deleted={}", deleted);
		} catch (RuntimeException exception) {
			log.error("[어드민 감사 로그] 정리 실패 — 내일 다시 돈다", exception);
		}
	}
}
