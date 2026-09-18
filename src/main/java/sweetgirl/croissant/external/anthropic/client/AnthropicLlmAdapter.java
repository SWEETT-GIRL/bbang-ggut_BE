package sweetgirl.croissant.external.anthropic.client;

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

import sweetgirl.croissant.external.anthropic.config.AnthropicProperties;
import sweetgirl.croissant.external.anthropic.dto.AnthropicMessageRequest;
import sweetgirl.croissant.external.anthropic.dto.AnthropicMessageResponse;
import sweetgirl.croissant.external.llm.LlmProvider;
import sweetgirl.croissant.external.llm.client.LlmClient;
import sweetgirl.croissant.external.llm.dto.LlmRequest;
import sweetgirl.croissant.external.llm.dto.LlmResult;
import sweetgirl.croissant.external.llm.exception.LlmErrorCode;
import sweetgirl.croissant.external.llm.exception.LlmException;

/**
 * Anthropic Messages API 어댑터 (#662).
 *
 * <p>공식 SDK 가 있지만 {@code RestClient} 로 직접 부른다 — 레포의 외부 API 다섯이 전부 이 방식이고, 세 벤더의
 * 지연을 나란히 재려면 클라이언트 계층이 같아야 공정하다(SDK 는 자체 재시도·타임아웃 규칙을 갖는다).
 * 스트리밍·툴·프롬프트 캐싱이 필요해지면 그때 이 어댑터만 SDK 로 바꾼다.
 */
@Component
public class AnthropicLlmAdapter implements LlmClient {

	private static final String MESSAGES_PATH = "/v1/messages";
	private static final int TOO_MANY_REQUESTS = 429;
	private static final int OVERLOADED = 529;

	private final RestClient anthropicRestClient;
	private final AnthropicProperties anthropicProperties;
	private final ObjectMapper objectMapper;

	public AnthropicLlmAdapter(
			@Qualifier("anthropicRestClient") RestClient anthropicRestClient,
			AnthropicProperties anthropicProperties,
			ObjectMapper objectMapper) {
		this.anthropicRestClient = anthropicRestClient;
		this.anthropicProperties = anthropicProperties;
		this.objectMapper = objectMapper;
	}

	@Override
	public LlmProvider provider() {
		return LlmProvider.ANTHROPIC;
	}

	@Override
	public boolean isConfigured() {
		return StringUtils.hasText(anthropicProperties.getKey());
	}

	@Override
	public String defaultModel() {
		return anthropicProperties.getModel();
	}

	@Override
	public <T> LlmResult<T> generateStructured(LlmRequest request, Class<T> responseType) {
		AnthropicMessageRequest body = AnthropicMessageRequest.structuredJson(
			request.model(),
			request.prompt(),
			request.responseSchema(),
			request.maxOutputTokens(),
			request.temperature());

		AnthropicMessageResponse response = call(body);
		if (response.isRefusal()) {
			throw new LlmException(LlmErrorCode.REFUSED);
		}
		if (response.isTruncated()) {
			throw new LlmException(LlmErrorCode.EMPTY_RESPONSE);
		}
		String rawText = response.firstText()
			.orElseThrow(() -> new LlmException(LlmErrorCode.EMPTY_RESPONSE));
		return new LlmResult<>(
			readValue(rawText, responseType),
			LlmProvider.ANTHROPIC,
			request.model(),
			0L,
			response.inputTokens(),
			response.outputTokens(),
			rawText);
	}

	private AnthropicMessageResponse call(AnthropicMessageRequest request) {
		AnthropicMessageResponse response;
		try {
			response = anthropicRestClient.post()
				.uri(MESSAGES_PATH)
				.contentType(MediaType.APPLICATION_JSON)
				.body(request)
				.retrieve()
				.body(AnthropicMessageResponse.class);
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
		if (status.value() == OVERLOADED || status.is5xxServerError()) {
			return LlmErrorCode.UNAVAILABLE;
		}
		return LlmErrorCode.GENERATE_FAILED;
	}
}
