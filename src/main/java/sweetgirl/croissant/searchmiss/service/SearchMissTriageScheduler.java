package sweetgirl.croissant.searchmiss.service;

import java.time.Instant;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.external.llm.exception.LlmException;
import sweetgirl.croissant.external.naver.exception.NaverSearchException;
import sweetgirl.croissant.searchmiss.config.SearchMissTriageProperties;

/**
 * 실패 검색어를 주기적으로 자동 판정한다.
 *
 * <p><b>한도에 걸리면 스스로 쉰다.</b> 네이버 검색 API(하루 25,000건)나 LLM 한도를 넘긴 뒤에도 주기마다 배치를 잡으면
 * 같은 행만 계속 실패한다 — {@code MenuClassificationScheduler}와 같은 이유로 {@code rateLimitBackoff}만큼 건너뛴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SearchMissTriageScheduler {

	private static final String FIXED_DELAY = "${search-miss.triage.fixed-delay-ms:300000}";
	private static final String INITIAL_DELAY = "${search-miss.triage.initial-delay-ms:120000}";

	private final SearchMissTriageService searchMissTriageService;
	private final SearchMissTriageProperties properties;

	/** 한도에 걸려 쉬는 중이면 이 시각 전까지 배치를 잡지 않는다 */
	private volatile Instant resumeAt = Instant.EPOCH;

	@Scheduled(fixedDelayString = FIXED_DELAY, initialDelayString = INITIAL_DELAY)
	public void triageNextBatch() {
		if (!properties.isEnabled() || Instant.now().isBefore(resumeAt)) {
			return;
		}
		try {
			SearchMissTriageBatchResult result = searchMissTriageService.triageNextBatch();
			if (result.total() > 0) {
				log.info("[검색어 판정] 배치 — {} autoApply={}", result, properties.isAutoApply());
			}
		} catch (NaverSearchException | LlmException exception) {
			resumeAt = Instant.now().plus(properties.getRateLimitBackoff());
			log.warn("[검색어 판정] 외부 API 한도 초과 — {} 까지 쉰다", resumeAt, exception);
		} catch (RuntimeException exception) {
			// 한 배치 실패가 스케줄러를 멈추게 두지 않는다. 판정이 안 찍힌 행은 다음 주기에 다시 집힌다
			log.warn("[검색어 판정] 배치 실패", exception);
		}
	}
}
