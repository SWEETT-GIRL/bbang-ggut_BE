package sweetgirl.croissant.searchmiss.service;

import java.time.Duration;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.searchmiss.config.SearchMissTriageProperties;

/**
 * 실패 검색어를 하루 한 번 새벽에 자동 판정하고, 결과를 Slack 으로 보고한다.
 *
 * <p>실패 검색어는 반나절 늦게 판정돼도 문제가 없다. 새벽에 몰아 돌리면 검색 probe 가 운영 DB 에 얹히는 부하가 한산한 시간에 가고,
 * 관리자는 아침에 결과를 한 번에 본다. 04:00 은 interaction 적재 배치가 쓰므로 06:00 에 둔다.
 *
 * <p>처리할 행이 없어도, 도중에 멈추거나 실패해도 리포트를 보낸다 — 리포트가 안 와 있으면 배치가 안 돈 것이다.
 * 꺼져 있으면 아무것도 보내지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SearchMissTriageScheduler {

	private static final String CRON = "${search-miss.triage.cron:0 0 6 * * *}";

	private final SearchMissTriageService searchMissTriageService;
	private final SearchMissTriageProperties properties;
	private final ApplicationEventPublisher eventPublisher;

	@Scheduled(cron = CRON, zone = "Asia/Seoul")
	public void triageDaily() {
		if (!properties.isEnabled()) {
			return;
		}
		long startedAt = System.nanoTime();
		boolean autoApply = properties.isAutoApply();
		try {
			SearchMissTriageBatchResult result = searchMissTriageService.triageNextBatch();
			log.info("[검색어 판정] 일일 배치 — total={} verdicts={} failed={} stopped={} autoApply={}",
				result.total(), result.verdicts(), result.failed(), result.stoppedReason(), autoApply);
			eventPublisher.publishEvent(SearchMissTriageReport.completed(result, autoApply, elapsedSince(startedAt)));
		} catch (RuntimeException exception) {
			// 배치 실패가 다음 날 스케줄까지 막지 않게 여기서 끊는다
			log.warn("[검색어 판정] 일일 배치 실패", exception);
			eventPublisher.publishEvent(SearchMissTriageReport.failed(exception, autoApply, elapsedSince(startedAt)));
		}
	}

	private static Duration elapsedSince(long startedAt) {
		return Duration.ofNanos(System.nanoTime() - startedAt);
	}
}
