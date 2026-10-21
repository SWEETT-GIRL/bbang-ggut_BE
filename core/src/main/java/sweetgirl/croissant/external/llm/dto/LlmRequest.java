package sweetgirl.croissant.external.llm.dto;

import java.util.Map;

import sweetgirl.croissant.external.llm.LlmProvider;

/**
 * 벤더에 상관없이 같은 모양으로 쓰는 요청 (#662).
 *
 * @param provider 어느 벤더에게 보낼지
 * @param model 모델 id. 실험이 모델을 갈아 끼우므로 설정이 아니라 요청이 들고 다닌다
 * @param prompt 완성된 프롬프트 한 덩어리. 시스템·사용자 분리는 벤더마다 달라 어댑터가 정한다
 * @param responseSchema 표준 JSON 스키마(소문자 타입 · {@code additionalProperties: false} · {@code required}).
 *        Anthropic · OpenAI 는 그대로 쓰고, Gemini 는 어댑터가 OpenAPI 부분집합으로 옮긴다
 * @param temperature 0 이면 같은 입력에 같은 답을 기대한다
 * @param maxOutputTokens 이유 네 줄이라 작게 잡는다. 넘치면 잘려서 JSON 이 깨진다
 */
public record LlmRequest(
		LlmProvider provider,
		String model,
		String prompt,
		Map<String, Object> responseSchema,
		double temperature,
		int maxOutputTokens) {
}
