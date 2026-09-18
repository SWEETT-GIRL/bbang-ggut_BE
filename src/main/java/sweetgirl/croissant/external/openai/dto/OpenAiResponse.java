package sweetgirl.croissant.external.openai.dto;

import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** {@code POST /v1/responses} 응답 — 쓰는 필드만 매핑한다 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenAiResponse(
		String status,
		List<OutputItem> output,
		@JsonProperty("incomplete_details") IncompleteDetails incompleteDetails,
		Usage usage) {

	private static final String MESSAGE_TYPE = "message";
	private static final String OUTPUT_TEXT_TYPE = "output_text";

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record OutputItem(String type, List<ContentPart> content) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ContentPart(String type, String text) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record IncompleteDetails(String reason) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Usage(
			@JsonProperty("input_tokens") int inputTokens,
			@JsonProperty("output_tokens") int outputTokens) {
	}

	/**
	 * 답이 담긴 텍스트. 추론 모델은 {@code output} 첫 자리에 reasoning 항목을 두므로 순서로 집지 않고
	 * {@code type} 으로 찾는다.
	 */
	public Optional<String> firstText() {
		if (output == null) {
			return Optional.empty();
		}
		return output.stream()
			.filter(item -> MESSAGE_TYPE.equals(item.type()) && item.content() != null)
			.flatMap(item -> item.content().stream())
			.filter(part -> OUTPUT_TEXT_TYPE.equals(part.type()))
			.map(ContentPart::text)
			.filter(text -> text != null && !text.isBlank())
			.findFirst();
	}

	/** 출력 상한에 걸려 잘렸나 — 잘린 JSON 은 파싱 오류가 아니라 상한 문제로 읽어야 고칠 곳을 안다 */
	public boolean isTruncated() {
		return incompleteDetails != null && "max_output_tokens".equals(incompleteDetails.reason());
	}

	public int inputTokens() {
		return usage == null ? 0 : usage.inputTokens();
	}

	public int outputTokens() {
		return usage == null ? 0 : usage.outputTokens();
	}
}
