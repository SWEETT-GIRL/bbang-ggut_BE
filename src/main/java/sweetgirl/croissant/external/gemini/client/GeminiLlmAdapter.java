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

		String rawText = response.firstText()
			.filter(StringUtils::hasText)
			.orElseThrow(() -> new LlmException(LlmErrorCode.EMPTY_RESPONSE));
		return new LlmResult<>(
			readValue(rawText, responseType),
			LlmProvider.GEMINI,
			request.model(),
			0L,
			response.promptTokens(),
			response.candidatesTokens(),
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
