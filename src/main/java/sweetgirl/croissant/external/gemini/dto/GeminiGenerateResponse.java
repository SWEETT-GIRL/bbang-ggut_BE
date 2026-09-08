package sweetgirl.croissant.external.gemini.dto;

import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** generateContent 응답 — 쓰는 필드만 매핑한다 (safetyRatings·modelVersion 등은 무시) */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GeminiGenerateResponse(
		List<Candidate> candidates,
		UsageMetadata usageMetadata) {

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Candidate(Content content, String finishReason) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Content(List<Part> parts) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Part(String text) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record UsageMetadata(int promptTokenCount, int candidatesTokenCount) {
	}

	/** 첫 후보의 첫 텍스트 파트. 구조화 출력이면 여기가 JSON 문자열 */
	public Optional<String> firstText() {
		if (candidates == null || candidates.isEmpty()) {
			return Optional.empty();
		}
		Content content = candidates.get(0).content();
		if (content == null || content.parts() == null || content.parts().isEmpty()) {
			return Optional.empty();
		}
		return Optional.ofNullable(content.parts().get(0).text());
	}

	public int promptTokens() {
		return usageMetadata == null ? 0 : usageMetadata.promptTokenCount();
	}

	public int candidatesTokens() {
		return usageMetadata == null ? 0 : usageMetadata.candidatesTokenCount();
	}
}
