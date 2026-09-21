package sweetgirl.croissant.external.openai.client;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import sweetgirl.croissant.external.llm.LlmProvider;
import sweetgirl.croissant.external.llm.client.LlmClient;
import sweetgirl.croissant.external.llm.dto.LlmRequest;
import sweetgirl.croissant.external.llm.dto.LlmResult;
import sweetgirl.croissant.external.llm.exception.LlmErrorCode;
import sweetgirl.croissant.external.llm.exception.LlmException;
import sweetgirl.croissant.external.openai.config.OpenAiProperties;
import sweetgirl.croissant.external.openai.dto.OpenAiResponse;
import sweetgirl.croissant.external.openai.dto.OpenAiResponseRequest;

/**
 * OpenAI Responses API 어댑터 (#662).
 *
 * <p>공식 SDK 대신 {@code RestClient} 로 직접 부른다 — 레포의 외부 API 다섯이 전부 이 방식이고, 세 벤더의 지연을
 * 나란히 재려면 클라이언트 계층이 같아야 공정하다. 재시도는 하지 않는다. 얼마나 쉬었다 다시 보낼지는 호출자가 정한다.
 */
@Component
public class OpenAiLlmAdapter implements LlmClient {

	private static final String RESPONSES_PATH = "/v1/responses";
	private static final String SCHEMA_NAME = "structured_output";
	private static final int TOO_MANY_REQUESTS = 429;

	private final RestClient openAiRestClient;
	private final OpenAiProperties openAiProperties;
	private final ObjectMapper objectMapper;

	public OpenAiLlmAdapter(
			@Qualifier("openAiRestClient") RestClient openAiRestClient,
			OpenAiProperties openAiProperties,
			ObjectMapper objectMapper) {
		this.openAiRestClient = openAiRestClient;
		this.openAiProperties = openAiProperties;
		this.objectMapper = objectMapper;
	}

	@Override
	public LlmProvider provider() {
		return LlmProvider.OPENAI;
	}

	@Override
	public boolean isConfigured() {
		return StringUtils.hasText(openAiProperties.getKey());
	}

	@Override
	public String defaultModel() {
		return openAiProperties.getModel();
	}

	@Override
	public <T> LlmResult<T> generateStructured(LlmRequest request, Class<T> responseType) {
		OpenAiResponseRequest body = OpenAiResponseRequest.structuredJson(
			request.model(),
			request.prompt(),
			SCHEMA_NAME,
			request.responseSchema(),
			request.maxOutputTokens(),
			openAiProperties.getReasoningEffort());

		OpenAiResponse response = call(body);
		if (response.isTruncated()) {
			throw new LlmException(LlmErrorCode.EMPTY_RESPONSE);
		}
		String rawText = response.firstText()
			.orElseThrow(() -> new LlmException(LlmErrorCode.EMPTY_RESPONSE));
		return new LlmResult<>(
			readValue(rawText, responseType),
			LlmProvider.OPENAI,
			request.model(),
			0L,
			response.inputTokens(),
			response.outputTokens(),
			response.reasoningTokens(),
			rawText);
	}

	private OpenAiResponse call(OpenAiResponseRequest request) {
		OpenAiResponse response;
		try {
			response = openAiRestClient.post()
				.uri(RESPONSES_PATH)
				.contentType(MediaType.APPLICATION_JSON)
				.body(request)
				.retrieve()
				.body(OpenAiResponse.class);
		} catch (RestClientResponseException e) {
			throw new LlmException(toErrorCode(e.getStatusCode()), e);
		} catch (RestClientException e) {
			throw new LlmException(LlmErrorCode.GENERATE_FAILED, e);
		}
		if (response == null) {
			throw new LlmException(LlmErrorCode.EMPTY_RESPONSE);
		}
		return response;
	}

	private <T> T readValue(String rawText, Class<T> responseType) {
		try {
			return objectMapper.readValue(rawText, responseType);
		} catch (JsonProcessingException e) {
			throw new LlmException(LlmErrorCode.INVALID_JSON, e);
		}
	}

	private LlmErrorCode toErrorCode(HttpStatusCode status) {
		if (status.value() == TOO_MANY_REQUESTS) {
			return LlmErrorCode.RATE_LIMITED;
		}
		if (status.is5xxServerError()) {
			return LlmErrorCode.UNAVAILABLE;
		}
		return LlmErrorCode.GENERATE_FAILED;
	}
}
