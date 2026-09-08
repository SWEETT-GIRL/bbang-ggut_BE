package sweetgirl.croissant.menu.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;

/**
 * 메뉴 AI 분류 스케줄러 설정.
 *
 * <p>기본값은 무료 티어(분당 15요청)에 맞춘다 — 1분마다 100건 배치 1개 = 요청 3개.
 * 결제 계정을 붙이면 fixed-delay 를 줄이거나 batch-size 를 키운다.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "menu.classification")
public class MenuClassificationProperties {

	/** false 면 스케줄러 빈 자체를 만들지 않는다 (테스트·로컬) */
	private boolean enabled = true;

	/** 한 배치에 주워 오는 메뉴 수. 프롬프트 한 번에 넣는 이름 수이기도 하다 */
	private int batchSize = 100;

	/** 같은 프롬프트를 몇 번 물어 일치를 볼지. 실측은 3회 기준 */
	private int runs = 3;

	/** 429·503 에 몇 번까지 다시 보낼지 */
	private int maxRetries = 3;

	/** 재시도 전 대기. 무료 티어 429 의 retryDelay 가 20초대였다 */
	private Duration retryBackoff = Duration.ofSeconds(30);

	private long fixedDelayMs = 60_000L;

	private long initialDelayMs = 30_000L;
}
