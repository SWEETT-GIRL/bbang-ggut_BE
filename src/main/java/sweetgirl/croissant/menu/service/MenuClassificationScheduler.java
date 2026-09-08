package sweetgirl.croissant.menu.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.menu.config.MenuClassificationProperties;
import sweetgirl.croissant.menu.dto.classification.MenuClassificationBatchResult;

/**
 * 미분류 메뉴를 주기적으로 AI 분류한다.
 *
 * <p>기본 1분 간격·100건이면 무료 티어(분당 15요청) 안에서 요청 3개. 월 신규 12만 건 중 룰이 못 잡는
 * 4만 건을 다 도는 데 7시간이면 충분하다. 결제 계정을 붙이면 fixed-delay 를 줄인다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MenuClassificationScheduler {

	private static final String FIXED_DELAY = "${menu.classification.fixed-delay-ms:60000}";
	private static final String INITIAL_DELAY = "${menu.classification.initial-delay-ms:30000}";

	private final MenuClassificationService menuClassificationService;
	private final MenuClassificationProperties properties;

	@Scheduled(fixedDelayString = FIXED_DELAY, initialDelayString = INITIAL_DELAY)
	public void classifyNextBatch() {
		if (!properties.isEnabled()) {
			return;
		}
		try {
			MenuClassificationBatchResult result = menuClassificationService.classifyNextBatch();
			if (result.total() > 0) {
				log.info("메뉴 AI 분류 배치 — {}", result);
			}
		} catch (RuntimeException e) {
			// 한 배치 실패가 스케줄러를 멈추게 두지 않는다. 다음 주기에 같은 행부터 다시 (classified_at 이 안 찍혔으니)
			log.warn("메뉴 AI 분류 배치 실패", e);
		}
	}
}
