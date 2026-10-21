package sweetgirl.croissant.external.anthropic.dto;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * {@code POST /v1/messages} 요청 본문 — 구조화 JSON 출력만 쓴다 (#662).
 *
 * <p>{@code output_config.format} 에 스키마를 걸면 모델이 그 모양의 JSON 만 내놓는다. 스키마에는
 * {@code additionalProperties: false} 와 전체 {@code required} 가 있어야 한다.
 *
 * <p><b>temperature 를 보내지 않는다.</b> 최신 모델은 이 값을 폐기했다 — Sonnet 5 에 보내면
 * {@code `temperature` is deprecated for this model} 로 400 이 온다(2026-09-18 실측). Haiku 4.5 는 아직
 * 받지만, 같은 어댑터가 모델마다 다른 본문을 보내면 비교 조건이 달라지므로 둘 다 안 보낸다. OpenAI 어댑터도
 * 같은 이유로 안 보낸다. 지금 이 값을 쓰는 벤더는 Gemini 뿐이다.
 */
public record AnthropicMessageRequest(
		String model,
		@JsonProperty("max_tokens") int maxTokens,
		List<Message> messages,
		@JsonProperty("output_config") OutputConfig outputConfig) {

	public record Message(String role, String content) {
	}

	public record OutputConfig(JsonSchemaFormat format) {
	}

	public record JsonSchemaFormat(String type, Map<String, Object> schema) {
	}

	public static AnthropicMessageRequest structuredJson(
			String model,
			String prompt,
			Map<String, Object> schema,
			int maxTokens) {
		return new AnthropicMessageRequest(
			model,
			maxTokens,
			List.of(new Message("user", prompt)),
			new OutputConfig(new JsonSchemaFormat("json_schema", schema)));
	}
}
