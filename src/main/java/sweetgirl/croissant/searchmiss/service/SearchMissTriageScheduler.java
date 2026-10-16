package sweetgirl.croissant.searchmiss.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.external.llm.exception.LlmException;
import sweetgirl.croissant.external.naver.exception.NaverSearchException;
import sweetgirl.croissant.searchmiss.config.SearchMissTriageProperties;

/**
 * 실패 검색어를 하루 한 번 새벽에 자동 판정한다.
 *
 * <p>실패 검색어는 반나절 늦게 판정돼도 문제가 없다. 새벽에 몰아 돌리면 검색 probe 가 운영 DB 에 얹히는 부하가 한산한 시간에 가고,
 * 관리자는 아침에 결과를 한 번에 본다. 04:00 은 interaction 적재 배치가 쓰므로 06:00 에 둔다.
 *
 * <p>네이버·LLM 한도에 걸리면 그 회차만 멈춘다. 판정이 안 찍힌 행은 다음 날 다시 집힌다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SearchMissTriageScheduler {

	private static final String CRON = "${search-miss.triage.cron:0 0 6 * * *}";

	private final SearchMissTriageService searchMissTriageService;
	private final SearchMissTriageProperties properties;

	@Scheduled(cron = CRON, zone = "Asia/Seoul")
	public void triageDaily() {
		if (!properties.isEnabled()) {
			return;
		}
		try {
			SearchMissTriageBatchResult result = searchMissTriageService.triageNextBatch();
			log.info("[검색어 판정] 일일 배치 — {} autoApply={}", result, properties.isAutoApply());
		} catch (NaverSearchException | LlmException exception) {
			log.warn("[검색어 판정] 외부 API 한도 초과로 이번 회차를 멈춘다 — 남은 행은 내일 다시 집힌다", exception);
		} catch (RuntimeException exception) {
			// 배치 실패가 다음 날 스케줄까지 막지 않게 여기서 끊는다
			log.warn("[검색어 판정] 일일 배치 실패", exception);
		}
	}
}
