package sweetgirl.croissant.recommendation.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import sweetgirl.croissant.recommendation.enums.ReasonPromptVariant;

/**
 * 이유 문장 실험의 판 크기 (#662).
 *
 * <p>모델과 변형을 코드가 아니라 설정에 둔다 — 1차 결과를 보고 모델을 더하거나 뺄 때 값 한 줄만 고치면 되게.
 * 요청이 같은 이름의 필드를 보내면 그쪽이 이긴다(한 번만 다르게 돌려 볼 때).
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "recommendation.reason-experiment")
public class ReasonExperimentProperties {

	/**
	 * 돌릴 모델. {@code 벤더:모델} 로 적는다 — 벤더는 {@code GEMINI · OPENAI · ANTHROPIC}.
	 * 기본값은 09-03 비용 계산기가 남긴 후보 넷(기준선 · 채택 후보 · 하한 · 상한 대조).
	 */
	private List<String> models = List.of(
		"GEMINI:gemini-3.5-flash-lite",
		"ANTHROPIC:claude-haiku-4-5-20251001",
		"OPENAI:gpt-5.6-luna",
		"ANTHROPIC:claude-sonnet-5");

	/** 돌릴 프롬프트 변형. 1차는 A(최소 재료)·D(룰의 선정 근거) */
	private List<ReasonPromptVariant> variants = List.of(ReasonPromptVariant.A, ReasonPromptVariant.D);

	/** 한 요청이 돌릴 수 있는 조합 수 상한 — 비용 가드. 모델 × 변형이 이 수를 넘으면 400 */
	@Min(1)
	private int maxCombinations = 12;

	/** 이유 네 줄이면 충분하다. 넘치면 잘려서 JSON 이 깨지므로 여유를 조금 둔다 */
	@Min(1)
	private int maxOutputTokens = 800;

	/** 0 — 같은 입력에 같은 문장을 기대한다. 비교가 목적이라 흔들리면 안 된다 */
	private double temperature = 0.0;
}
