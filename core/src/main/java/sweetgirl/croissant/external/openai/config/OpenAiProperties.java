package sweetgirl.croissant.external.openai.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;

/**
 * OpenAI Responses API 설정 (#662). 키는 CD secrets({@code OPENAI_API_KEY})에서만 받고 나머지는 기본값을 둔다.
 *
 * <p>키가 비어 있으면 어댑터가 스스로 "설정 안 됨"이라고 답해 실험이 그 벤더를 건너뛴다 — 세 벤더 키를 다 갖춘
 * 환경에서만 돌게 만들면 로컬에서 아무것도 못 해 보기 때문.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "openai.api")
public class OpenAiProperties {

	private String baseUrl = "https://api.openai.com";

	private String key;

	/**
	 * 이유 네 줄에 상한선 모델을 쓸 이유가 없어 최저가 티어로 시작한다. 2026-09-18 확인: gpt-5 계열은 sol · terra ·
	 * luna · cyber 뿐이고 nano · mini 티어는 없어졌다. luna 가 최저가($0.20 in / $1.20 out)이자 "요약·분류·초안" 용도다.
	 */
	private String model = "gpt-5.6-luna";

	/**
	 * 추론 예산. 이유 문장은 생각할 거리가 없는 일이라 낮게 둔다 — 높이면 첫 글자까지의 시간이 그대로 늘어난다.
	 * 받는 값은 none · low · medium · high · xhigh · max(2026-09-18 확인). 빈 값이면 아예 안 보내고 모델 기본값에 맡긴다.
	 */
	private String reasoningEffort = "low";

	private Duration connectTimeout = Duration.ofSeconds(5);

	private Duration readTimeout = Duration.ofSeconds(60);
}
