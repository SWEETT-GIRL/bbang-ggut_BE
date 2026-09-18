package sweetgirl.croissant.external.gemini.client;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.external.gemini.config.GeminiProperties;
import sweetgirl.croissant.external.gemini.dto.GeminiGenerateRequest;
import sweetgirl.croissant.external.gemini.dto.GeminiGenerateResponse;
import sweetgirl.croissant.external.gemini.exception.GeminiErrorCode;
import sweetgirl.croissant.external.gemini.exception.GeminiException;
import sweetgirl.croissant.external.llm.LlmProvider;
import sweetgirl.croissant.external.llm.client.LlmClient;
import sweetgirl.croissant.external.llm.dto.LlmRequest;
import sweetgirl.croissant.external.llm.dto.LlmResult;
import sweetgirl.croissant.external.llm.exception.LlmErrorCode;
import sweetgirl.croissant.external.llm.exception.LlmException;

/** Gemini 를 공통 {@link LlmClient} 계약에 맞춰 준다 (#662). HTTP·오류 매핑은 {@link GeminiClient} 가 이미 한다 */
@Component
@RequiredArgsConstructor
public class GeminiLlmAdapter implements LlmClient {

	private final GeminiClient geminiClient;
	private final GeminiProperties geminiProperties;
	private final ObjectMapper objectMapper;

	@Override
	public LlmProvider provider() {
		return LlmProvider.GEMINI;
	}

	@Override
	public boolean isConfigured() {
		return StringUtils.hasText(geminiProperties.getKey());
	}

	@Override
	public String defaultModel() {
		return geminiProperties.getModel();
	}

	@Override
	public <T> LlmResult<T> generateStructured(LlmRequest request, Class<T> responseType) {
		GeminiGenerateRequest body = GeminiGenerateRequest.structuredJson(
			request.prompt(),
			GeminiSchemaTranslator.translate(request.responseSchema()),
			request.temperature(),
			request.maxOutputTokens());

		GeminiGenerateResponse response;
		try {
			response = geminiClient.generate(request.model(), body);
		} catch (GeminiException e) {
			throw new LlmException(toErrorCode(e.getGeminiErrorCode()), e);
		}

		// 잘린 응답은 JSON 조각이라 파싱하면 INVALID_JSON 이 되는데, 고칠 곳은 프롬프트가 아니라 출력 상한이다.
		// 다른 두 어댑터와 같은 코드로 올려 표에서 원인이 같게 읽히게 한다.
		if (response.isTruncated()) {
			throw new LlmException(LlmErrorCode.EMPTY_RESPONSE);
		}
		String rawText = response.firstText()
			.filter(StringUtils::hasText)
			.orElseThrow(() -> new LlmException(LlmErrorCode.EMPTY_RESPONSE));
		return new LlmResult<>(
			readValue(rawText, responseType),
			LlmProvider.GEMINI,
			request.model(),
			0L,
			response.promptTokens(),
			response.billedOutputTokens(),
			response.thoughtsTokens(),
			rawText);
	}

	private <T> T readValue(String rawText, Class<T> responseType) {
		try {
			return objectMapper.readValue(rawText, responseType);
		} catch (JsonProcessingException e) {
			throw new LlmException(LlmErrorCode.INVALID_JSON, e);
		}
	}

	private LlmErrorCode toErrorCode(GeminiErrorCode errorCode) {
		return switch (errorCode) {
			case RATE_LIMITED -> LlmErrorCode.RATE_LIMITED;
			case UNAVAILABLE -> LlmErrorCode.UNAVAILABLE;
			case EMPTY_RESPONSE -> LlmErrorCode.EMPTY_RESPONSE;
			case INVALID_JSON -> LlmErrorCode.INVALID_JSON;
			case GENERATE_FAILED -> LlmErrorCode.GENERATE_FAILED;
		};
	}
}
