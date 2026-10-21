package sweetgirl.croissant.recommendation.dto.response;

import java.util.List;

import sweetgirl.croissant.external.llm.LlmProvider;
import sweetgirl.croissant.recommendation.enums.ReasonPromptVariant;

/**
 * 조합(벤더 × 모델 × 변형) 하나의 결과와 계측값 (#662).
 *
 * <p>한 번의 값으로는 "얼마나 걸리나"를 정할 수 없어 같은 조합을 여러 번 부르고 중앙값과 최댓값을 함께 준다.
 * 중앙값은 보통 얼마나 걸리는지, 최댓값은 나쁜 날에 회원이 얼마나 기다리는지다.
 *
 * @param samples 성공한 호출 수. 이 수가 0 이면 문장이 없고 {@code failure} 만 남는다
 * @param failedSamples 실패한 호출 수. 0 보다 크면 그 조합은 불안정하다
 * @param outputTokens 과금되는 출력 토큰. 사고 토큰을 포함한다
 * @param reasoningTokens 그중 사고 몫. Anthropic 은 가를 수 없어 0 이다
 */
public record ReasonExperimentRun(
		String label,
		LlmProvider provider,
		String model,
		ReasonPromptVariant variant,
		int samples,
		int failedSamples,
		long latencyMedianMs,
		long latencyMaxMs,
		int inputTokens,
		int outputTokens,
		int reasoningTokens,
		int promptChars,
		List<ReasonSentence> reasons,
		String failure) {
}
