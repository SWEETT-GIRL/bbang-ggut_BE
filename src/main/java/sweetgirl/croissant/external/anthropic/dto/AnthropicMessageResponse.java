package sweetgirl.croissant.external.anthropic.dto;

import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** {@code POST /v1/messages} 응답 — 쓰는 필드만 매핑한다 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AnthropicMessageResponse(
		List<ContentBlock> content,
		@JsonProperty("stop_reason") String stopReason,
		Usage usage) {

	private static final String TEXT_TYPE = "text";
	private static final String REFUSAL_STOP_REASON = "refusal";
	private static final String MAX_TOKENS_STOP_REASON = "max_tokens";

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ContentBlock(String type, String text) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Usage(
			@JsonProperty("input_tokens") int inputTokens,
			@JsonProperty("output_tokens") int outputTokens) {
	}

	/** 구조화 출력이면 첫 텍스트 블록이 JSON 문자열이다 */
	public Optional<String> firstText() {
		if (content == null) {
			return Optional.empty();
		}
		return content.stream()
			.filter(block -> TEXT_TYPE.equals(block.type()))
			.map(ContentBlock::text)
			.filter(text -> text != null && !text.isBlank())
			.findFirst();
	}

	/** 안전 분류기가 막았나 — 여기만 거부를 HTTP 200 에 담아 보내므로 본문을 보고 알아야 한다 */
	public boolean isRefusal() {
		return REFUSAL_STOP_REASON.equals(stopReason);
	}

	/** 출력 상한에 걸려 잘렸나 — 잘린 JSON 은 파싱 오류가 아니라 상한 문제로 읽어야 고칠 곳을 안다 */
	public boolean isTruncated() {
		return MAX_TOKENS_STOP_REASON.equals(stopReason);
	}

	public int inputTokens() {
		return usage == null ? 0 : usage.inputTokens();
	}

	public int outputTokens() {
		return usage == null ? 0 : usage.outputTokens();
	}
}
