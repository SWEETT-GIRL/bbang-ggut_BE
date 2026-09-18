package sweetgirl.croissant.external.anthropic.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;

/**
 * Anthropic Messages API 설정 (#662). 키는 CD secrets({@code CLAUDE_API_KEY})에서만 받는다.
 *
 * <p>키가 비어 있으면 어댑터가 "설정 안 됨"이라고 답해 실험이 그 벤더를 건너뛴다.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "anthropic.api")
public class AnthropicProperties {

	private String baseUrl = "https://api.anthropic.com";

	private String key;

	/** 이유 네 줄에 큰 모델을 쓸 이유가 없어 최저가 티어로 시작한다. 상한 대조는 요청이 모델을 직접 지정한다 */
	private String model = "claude-haiku-4-5-20251001";

	/** 날짜형 버전 고정. 이 값이 바뀌면 응답 모양이 바뀔 수 있어 설정으로 둔다 */
	private String version = "2023-06-01";

	private Duration connectTimeout = Duration.ofSeconds(5);

	private Duration readTimeout = Duration.ofSeconds(60);
}
