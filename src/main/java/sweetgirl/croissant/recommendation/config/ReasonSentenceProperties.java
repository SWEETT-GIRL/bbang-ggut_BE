package sweetgirl.croissant.recommendation.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import sweetgirl.croissant.external.llm.LlmProvider;
import sweetgirl.croissant.external.llm.dto.LlmModelRef;

import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import sweetgirl.croissant.recommendation.enums.ReasonPromptVariant;

/**
 * 앱이 부르는 이유 문장(#764)의 설정. 실험({@code recommendation.reason-experiment})과 다른 묶음이다 — 실험은 여러 조합을
 * 재는 판이고 여기는 사용자 한 사람에게 나가는 한 번의 호출이라, 모델도 상한도 따로 정해야 한다.
 *
 * <p>기본이 꺼짐인 이유: 운영에 이 코드가 배포돼도 아무 호출이 나가지 않아야 한다. 스테이징 CD 만 켠다.
 */
@Getter
@Setter
@Validated
@Component
@ConfigurationProperties(prefix = "recommendation.reason.llm")
public class ReasonSentenceProperties {

	/** 꺼져 있으면 LLM 을 부르지 않고 룰 문장을 그대로 준다(fallback = true). 앱은 갈래가 하나다 */
	private boolean enabled = false;

	/** {@code 벤더:모델}. 랩 실측에서 한 판 1.3~1.4초로 가장 빨랐던 조합이 기본값이다 */
	private String model = "GEMINI:gemini-3.5-flash-lite";

	/**
	 * 쓸 프롬프트 변형. 선정 근거를 붙이는 D 는 쓸 수 없다 — 저장된 추천에는 인기도 점수가 없어 근거 줄의 절반이 0 으로
	 * 나간다. 기동 때 막는다(설정 잘못은 첫 요청이 아니라 배포에서 드러나야 한다).
	 */
	private ReasonPromptVariant variant = ReasonPromptVariant.A;

	/**
	 * 이 시간을 넘으면 기다리지 않고 룰 문장을 준다. 랩 중앙값 1.3~1.4초의 세 배쯤 — 꼬리를 조금 기다려 주되 사용자가
	 * "멈췄다" 고 느끼기 전에 끊는다. 끊어도 호출 자체는 벤더 쪽 읽기 타임아웃까지 스레드를 쥐고 있다.
	 */
	private Duration timeout = Duration.ofSeconds(4);

	/**
	 * 이 경로의 HTTP 읽기 타임아웃. 공용 Gemini 클라이언트(60초)를 그대로 쓰면, 기다림을 끊어도 그 호출은 1분 동안
	 * 전용 줄의 스레드를 쥐고 있다(끊기가 HTTP 읽기를 멈추지는 못한다). 4초만 기다릴 경로에 1분짜리 클라이언트를
	 * 쓸 이유가 없어 따로 둔다 — 실험 · 메뉴 분류는 오래 기다려도 되므로 공용 클라이언트는 건드리지 않는다(#765 리뷰).
	 */
	private Duration readTimeout = Duration.ofSeconds(10);

	/**
	 * 하루 호출 수 상한 — Gemini 무료 등급 하루 500회를 메뉴 분류와 나눠 쓴다. 인스턴스 메모리 카운터라 스테이징(1대)에서만
	 * 뜻이 있고, 여러 대가 되면 공유 저장소로 옮겨야 한다. 한국 날짜(Asia/Seoul) 자정에 0 으로 돌아간다.
	 */
	@Min(0)
	private int dailyCallLimit = 200;

	/** 0 — 같은 추천을 두 번 눌러도 같은 문장이 나오게. 문장을 바꿔 보는 것은 실험 API 의 일이다 */
	private double temperature = 0.0;

	/** 이유 네 줄이면 충분하다. 넘치면 잘려서 JSON 이 깨지므로 여유를 조금 둔다 */
	@Min(1)
	private int maxOutputTokens = 800;

	public LlmProvider provider() {
		return modelRef().provider();
	}

	public String modelName() {
		return modelRef().model();
	}

	/**
	 * 추천 행에 남기는 프롬프트 규칙 버전 — 나중에 "어느 프롬프트가 쓴 문장인가" 를 문장만 보고는 알 수 없다.
	 * 변형에서 만들어 설정 항목을 하나 줄인다(실험과 같은 변형 이름이라 실험 표와 그대로 이어진다).
	 */
	public String promptVersion() {
		return "reason-" + variant.name();
	}

	private LlmModelRef modelRef() {
		return LlmModelRef.parse(model);
	}

}
