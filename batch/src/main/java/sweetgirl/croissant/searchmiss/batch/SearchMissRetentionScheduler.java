package sweetgirl.croissant.searchmiss.batch;

import java.time.LocalDateTime;

import org.springframework.stereotype.Component;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.batch.job.BatchJob;
import sweetgirl.croissant.searchmiss.config.SearchMissTriageProperties;
import sweetgirl.croissant.searchmiss.service.SearchMissTriageCommandService;

/**
 * 처리 완료 후 보관 기간(기본 90일)이 지난 실패 검색어를 매일 지운다.
 *
 * <p>검색어에는 사람 이름·이메일 같은 개인정보가 섞여 들어온다(운영 1회차 10/17 이메일 1건). 삭제 쿼리는 처음부터 있었지만 부르는 곳이
 * 없어 처리된 행이 계속 쌓였다. PENDING 은 대상이 아니다 — 아직 아무도 판단하지 않은 행이다. 검색어 교정은 별도 테이블이라 남는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SearchMissRetentionScheduler {

	private final SearchMissTriageCommandService searchMissTriageCommandService;
	private final SearchMissTriageProperties properties;

	@BatchJob("search-miss-retention")
	@SchedulerLock(name = "SearchMissRetentionScheduler_deleteHandled", lockAtMostFor = "PT10M")
	public void deleteHandled() {
		try {
			long deleted = searchMissTriageCommandService.deleteHandledBefore(
				LocalDateTime.now().minusDays(properties.getRetentionDays()));
			if (deleted > 0) {
				log.info("[실패 검색어 정리] 처리 후 {}일 지난 행 {}건 삭제", properties.getRetentionDays(), deleted);
			}
		} catch (RuntimeException exception) {
			log.error("[실패 검색어 정리] 실패 — 내일 다시 돈다", exception);
		}
	}
}
