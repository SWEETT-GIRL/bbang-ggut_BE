package sweetgirl.croissant.external.openai.dto;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * {@code POST /v1/responses} 요청 본문 — 구조화 JSON 출력만 쓴다 (#662).
 *
 * <p>Chat Completions 가 아니라 Responses 를 쓰는 이유는 gpt-5 계열의 추론 예산을 여기서만 지정할 수 있어서다.
 * temperature 는 안 보낸다 — 추론 모델이 1 외의 값을 거부한다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OpenAiResponseRequest(
		String model,
		String input,
		@JsonProperty("max_output_tokens") Integer maxOutputTokens,
		TextConfig text,
		Reasoning reasoning) {

	public record TextConfig(JsonSchemaFormat format) {
	}

	/** {@code strict: true} 는 스키마에 {@code additionalProperties: false} 와 전체 {@code required} 를 요구한다 */
	public record JsonSchemaFormat(
			String type,
			String name,
			boolean strict,
			Map<String, Object> schema) {

		public static JsonSchemaFormat of(String name, Map<String, Object> schema) {
			return new JsonSchemaFormat("json_schema", name, true, schema);
		}
	}

	public record Reasoning(String effort) {
	}

	public static OpenAiResponseRequest structuredJson(
			String model,
			String prompt,
			String schemaName,
			Map<String, Object> schema,
			Integer maxOutputTokens,
			String reasoningEffort) {
		return new OpenAiResponseRequest(
			model,
			prompt,
			maxOutputTokens,
			new TextConfig(JsonSchemaFormat.of(schemaName, schema)),
			reasoningEffort == null || reasoningEffort.isBlank() ? null : new Reasoning(reasoningEffort));
	}
}
