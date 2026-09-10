package sweetgirl.croissant.external.gemini.dto;

import java.util.Base64;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/** {@code POST /v1beta/models/{model}:generateContent} 요청 본문 — 구조화 JSON 출력만 쓴다 */
public record GeminiGenerateRequest(
		List<Content> contents,
		GenerationConfig generationConfig) {

	public record Content(List<Part> parts) {
	}

	/**
	 * 파트 하나는 텍스트이거나 이미지다 — 스펙이 {@code {"text": ...}} 또는
	 * {@code {"inlineData": {...}}} 중 하나만 받으므로 안 쓰는 쪽은 직렬화에서 빠져야 한다.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Part(String text, InlineData inlineData) {

		public static Part ofText(String text) {
			return new Part(text, null);
		}

		public static Part ofImage(String mimeType, byte[] image) {
			return new Part(null, new InlineData(mimeType, Base64.getEncoder().encodeToString(image)));
		}
	}

	/** 이미지를 요청 본문에 그대로 싣는다 — 파일 API 에 먼저 올리지 않고 한 번에 보낸다 */
	public record InlineData(String mimeType, String data) {
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
			List.of(new Content(List.of(Part.ofText(prompt)))),
			new GenerationConfig("application/json", responseSchema, temperature));
	}

	/** 이미지가 먼저, 프롬프트가 나중이다 — 지시문이 "앞의 사진"을 가리키게 하려면 순서가 이래야 한다 */
	public static GeminiGenerateRequest structuredJsonWithImage(
			String prompt,
			String mimeType,
			byte[] image,
			Map<String, Object> responseSchema,
			double temperature) {
		return new GeminiGenerateRequest(
			List.of(new Content(List.of(Part.ofImage(mimeType, image), Part.ofText(prompt)))),
			new GenerationConfig("application/json", responseSchema, temperature));
	}
}
