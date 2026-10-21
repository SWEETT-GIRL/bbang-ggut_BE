package sweetgirl.croissant.member.batch;

import java.time.LocalDateTime;

import org.springframework.stereotype.Component;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.batch.job.BatchJob;
import sweetgirl.croissant.member.service.WithdrawnMemberRetentionService;

/** 매일 04:40 KST — 보관 기한이 지난 탈퇴 회원의 남은 개인 정보를 지운다. 실패하면 다음 날 다시 돈다(같은 일을 또 해도 같다). */
@Slf4j
@Component
@RequiredArgsConstructor
public class WithdrawnMemberRetentionScheduler {

	private final WithdrawnMemberRetentionService retentionService;

	@BatchJob("withdrawn-member-retention")
	@SchedulerLock(name = "WithdrawnMemberRetentionScheduler_anonymizeExpired", lockAtMostFor = "PT5M")
	public void anonymizeExpired() {
		try {
			retentionService.anonymizeExpired(LocalDateTime.now());
		} catch (RuntimeException exception) {
			log.error("[탈퇴 회원 정리] 실패 — 내일 다시 돈다", exception);
		}
	}
}
