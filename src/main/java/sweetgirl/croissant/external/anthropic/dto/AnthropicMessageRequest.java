package sweetgirl.croissant.external.anthropic.dto;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * {@code POST /v1/messages} 요청 본문 — 구조화 JSON 출력만 쓴다 (#662).
 *
 * <p>{@code output_config.format} 에 스키마를 걸면 모델이 그 모양의 JSON 만 내놓는다. 스키마에는
 * {@code additionalProperties: false} 와 전체 {@code required} 가 있어야 한다.
 */
public record AnthropicMessageRequest(
		String model,
		@JsonProperty("max_tokens") int maxTokens,
		double temperature,
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
			int maxTokens,
			double temperature) {
		return new AnthropicMessageRequest(
			model,
			maxTokens,
			temperature,
			List.of(new Message("user", prompt)),
			new OutputConfig(new JsonSchemaFormat("json_schema", schema)));
	}
}
