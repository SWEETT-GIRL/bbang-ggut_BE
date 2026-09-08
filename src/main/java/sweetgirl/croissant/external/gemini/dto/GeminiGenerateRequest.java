package sweetgirl.croissant.external.gemini.dto;

import java.util.List;
import java.util.Map;

/** {@code POST /v1beta/models/{model}:generateContent} 요청 본문 — 구조화 JSON 출력만 쓴다 */
public record GeminiGenerateRequest(
		List<Content> contents,
		GenerationConfig generationConfig) {

	public record Content(List<Part> parts) {
	}

	public record Part(String text) {
	}

	/**
	 * @param responseSchema OpenAPI 스키마 — enum 목록이 런타임에 정해지므로(MenuKind) Map 으로 받는다
	 */
	public record GenerationConfig(
			String responseMimeType,
			Map<String, Object> responseSchema,
			double temperature) {
	}

	public static GeminiGenerateRequest structuredJson(
			String prompt,
			Map<String, Object> responseSchema,
			double temperature) {
		return new GeminiGenerateRequest(
			List.of(new Content(List.of(new Part(prompt)))),
			new GenerationConfig("application/json", responseSchema, temperature));
	}
}
