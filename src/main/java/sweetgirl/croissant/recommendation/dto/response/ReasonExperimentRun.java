package sweetgirl.croissant.recommendation.dto.response;

import java.util.List;

import sweetgirl.croissant.external.llm.LlmProvider;
import sweetgirl.croissant.recommendation.enums.ReasonPromptVariant;

/**
 * 조합(벤더 × 모델 × 변형) 하나의 결과와 계측값 (#662).
 *
 * @param latencyMs 요청 직전부터 값이 손에 들어오기까지. 이 값이 곧 "카드가 언제 뜨나"
 * @param failure 실패했을 때의 코드·사유. 한 조합이 실패해도 나머지는 계속 돈다
 */
public record ReasonExperimentRun(
		String label,
		LlmProvider provider,
		String model,
		ReasonPromptVariant variant,
		long latencyMs,
		int inputTokens,
		int outputTokens,
		int promptChars,
		List<ReasonSentence> reasons,
		String failure) {

	public static ReasonExperimentRun succeeded(String label, LlmProvider provider, String model,
			ReasonPromptVariant variant, long latencyMs, int inputTokens, int outputTokens, int promptChars,
			List<ReasonSentence> reasons) {
		return new ReasonExperimentRun(label, provider, model, variant, latencyMs, inputTokens, outputTokens,
			promptChars, reasons, null);
	}

	public static ReasonExperimentRun failed(String label, LlmProvider provider, String model,
			ReasonPromptVariant variant, long latencyMs, int promptChars, String failure) {
		return new ReasonExperimentRun(label, provider, model, variant, latencyMs, 0, 0, promptChars, List.of(),
			failure);
	}
}
