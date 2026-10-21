package sweetgirl.croissant.external.gemini.dto;

import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** generateContent 응답 — 쓰는 필드만 매핑한다 (safetyRatings·modelVersion 등은 무시) */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GeminiGenerateResponse(
		List<Candidate> candidates,
		UsageMetadata usageMetadata) {

	private static final String MAX_TOKENS_FINISH_REASON = "MAX_TOKENS";

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
	/**
	 * thoughtsTokenCount 는 사고 토큰이다 — candidatesTokenCount 에 안 들어가지만 과금은 된다.
	 * <b>사고를 안 한 응답에는 이 필드 자체가 없다.</b> 그래서 Integer 다 — int 로 두면 필드가 빠진 평범한 응답이
	 * "Cannot map null into type int" 로 통째로 깨진다.
	 */
	public record UsageMetadata(int promptTokenCount, int candidatesTokenCount, Integer thoughtsTokenCount) {
	}

	/**
	 * 출력 상한에 걸려 잘렸나 — 잘린 JSON 은 파싱 오류가 아니라 상한 문제로 읽어야 고칠 곳을 안다.
	 * Anthropic 의 {@code stop_reason=max_tokens}, OpenAI 의 {@code incomplete_details} 와 같은 자리다.
	 */
	public boolean isTruncated() {
		if (candidates == null || candidates.isEmpty()) {
			return false;
		}
		return MAX_TOKENS_FINISH_REASON.equals(candidates.get(0).finishReason());
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

	/** 사고 토큰 — 응답 텍스트에는 안 보이지만 과금된다 */
	public int thoughtsTokens() {
		if (usageMetadata == null || usageMetadata.thoughtsTokenCount() == null) {
			return 0;
		}
		return usageMetadata.thoughtsTokenCount();
	}

	/** 과금되는 출력 토큰 전부. 다른 벤더의 output_tokens 와 같은 뜻이 되게 사고 몫을 합친다 */
	public int billedOutputTokens() {
		return candidatesTokens() + thoughtsTokens();
	}
}
