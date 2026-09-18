package sweetgirl.croissant.external.llm.dto;

import sweetgirl.croissant.external.llm.LlmProvider;

/**
 * 호출 한 번의 결과와 계측값 (#662).
 *
 * @param value 응답 JSON 을 요청한 타입으로 역직렬화한 것
 * @param latencyMs 요청 직전부터 파싱까지. 이 값이 곧 "카드가 언제 뜨나" 다 — {@code LlmClientRegistry} 가 채운다
 * @param rawText 모델이 돌려준 JSON 원문. 프롬프트를 고칠 때 눈으로 보려고 남긴다
 */
public record LlmResult<T>(
		T value,
		LlmProvider provider,
		String model,
		long latencyMs,
		int inputTokens,
		int outputTokens,
		String rawText) {

	/** 어댑터는 지연을 모른 채 돌려주고, 레지스트리가 재서 채운다. */
	public LlmResult<T> withLatency(long measuredMs) {
		return new LlmResult<>(value, provider, model, measuredMs, inputTokens, outputTokens, rawText);
	}
}
