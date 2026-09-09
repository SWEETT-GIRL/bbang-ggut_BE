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

	/**
	 * 재시도까지 다 쓰고도 429 면 이만큼 배치를 건너뛴다.
	 *
	 * <p>왜 필요한가 — 무료 티어는 분당뿐 아니라 <b>하루 500요청</b>도 막는다(2026-09-09 실측).
	 * 그 한도를 넘긴 뒤에도 1분마다 배치를 잡으면, 30초씩 세 번 재시도하고 배치가 통째로 실패한다.
	 * 실패한 배치는 classified_at 이 안 찍혀 다음 주기에 같은 100건을 또 집는다 — 진도는 안 나가면서
	 * 남은 할당량만 태운다. 실측으로 6시간에 186건 처리, 대기열 23,269건이 그대로였다.
	 *
	 * <p>1시간인 이유 — 분당 한도였다면 금방 풀리고, 하루 한도였다면 하루에 24번만 헛치면 된다.
	 * 결제 계정을 붙이면 애초에 여기 안 걸린다.
	 */
	private Duration rateLimitBackoff = Duration.ofHours(1);

	/** 회원 메뉴 등록 화면의 1회 즉답 상한 — 사용자가 기다리는 경로라 짧게. 넘기면 제안 없이 진행 */
	private Duration suggestTimeout = Duration.ofSeconds(3);

	private long fixedDelayMs = 60_000L;

	private long initialDelayMs = 30_000L;
}
