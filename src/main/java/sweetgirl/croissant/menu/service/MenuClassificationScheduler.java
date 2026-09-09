package sweetgirl.croissant.menu.service;

import java.time.Instant;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.external.gemini.exception.GeminiErrorCode;
import sweetgirl.croissant.external.gemini.exception.GeminiException;
import sweetgirl.croissant.menu.config.MenuClassificationProperties;
import sweetgirl.croissant.menu.dto.classification.MenuClassificationBatchResult;

/**
 * 미분류 메뉴를 주기적으로 AI 분류한다.
 *
 * <p>기본 1분 간격·100건이면 무료 티어(분당 15요청) 안에서 요청 3개. 월 신규 12만 건 중 룰이 못 잡는
 * 4만 건을 다 도는 데 7시간이면 충분하다. 결제 계정을 붙이면 fixed-delay 를 줄인다.
 *
 * <p><b>한도에 걸리면 스스로 쉰다.</b> 무료 티어는 분당뿐 아니라 하루 총량도 막는데, 그걸 넘긴 뒤에도
 * 1분마다 배치를 잡으면 재시도만 하다 실패하고 다음 주기에 같은 행을 또 집는다 — 진도는 안 나가면서
 * 남은 할당량만 태운다. 그래서 429 를 끝까지 맞은 뒤에는 {@code rateLimitBackoff} 만큼 건너뛴다.
 * 회복되면 알아서 다시 돈다 — 사람이 설정을 고쳐 배포할 일이 없다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MenuClassificationScheduler {

	private static final String FIXED_DELAY = "${menu.classification.fixed-delay-ms:60000}";
	private static final String INITIAL_DELAY = "${menu.classification.initial-delay-ms:30000}";

	private final MenuClassificationService menuClassificationService;
	private final MenuClassificationProperties properties;

	/** 한도에 걸려 쉬는 중이면 이 시각 전까지 배치를 잡지 않는다. 스케줄러 스레드 밖에서도 읽으므로 volatile */
	private volatile Instant resumeAt = Instant.EPOCH;

	@Scheduled(fixedDelayString = FIXED_DELAY, initialDelayString = INITIAL_DELAY)
	public void classifyNextBatch() {
		if (!properties.isEnabled() || Instant.now().isBefore(resumeAt)) {
			return;
		}
		try {
			MenuClassificationBatchResult result = menuClassificationService.classifyNextBatch();
			if (result.total() > 0) {
				log.info("메뉴 AI 분류 배치 — {}", result);
			}
		} catch (GeminiException exception) {
			if (exception.getGeminiErrorCode() == GeminiErrorCode.RATE_LIMITED) {
				resumeAt = Instant.now().plus(properties.getRateLimitBackoff());
				log.warn("Gemini 한도 초과 — {} 까지 분류를 쉰다", resumeAt);
				return;
			}
			log.warn("메뉴 AI 분류 배치 실패", exception);
		} catch (RuntimeException exception) {
			// 한 배치 실패가 스케줄러를 멈추게 두지 않는다. 다음 주기에 같은 행부터 다시 (classified_at 이 안 찍혔으니)
			log.warn("메뉴 AI 분류 배치 실패", exception);
		}
	}
}
