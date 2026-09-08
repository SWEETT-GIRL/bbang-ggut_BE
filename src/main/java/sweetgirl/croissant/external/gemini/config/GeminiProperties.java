package sweetgirl.croissant.external.gemini.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;

/**
 * Gemini API 설정. 키만 외부(local 프로파일 / CD secrets)에서 받고 나머지는 기본값을 둔다.
 *
 * <p>readTimeout 을 따로 두는 이유 — 공통 {@code spring.http.clients.read-timeout} 은 3초인데
 * 메뉴 100건 배치 분류는 실측 4~20초가 걸린다.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "gemini.api")
public class GeminiProperties {

	private String baseUrl = "https://generativelanguage.googleapis.com";

	private String key;

	/** 2.5-flash-lite 는 신규 사용자에게 닫혔다(404). 실측(2026-09-07) 기준 최저가 티어이자 정확도 충분 */
	private String model = "gemini-3.5-flash-lite";

	private Duration connectTimeout = Duration.ofSeconds(5);

	private Duration readTimeout = Duration.ofSeconds(60);
}
