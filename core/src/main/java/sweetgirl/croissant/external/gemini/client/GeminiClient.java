package sweetgirl.croissant.external.gemini.client;

import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import sweetgirl.croissant.external.gemini.config.GeminiProperties;
import sweetgirl.croissant.external.gemini.dto.GeminiGenerateRequest;
import sweetgirl.croissant.external.gemini.dto.GeminiGenerateResponse;
import sweetgirl.croissant.external.gemini.dto.GeminiStructuredResult;
import sweetgirl.croissant.external.gemini.exception.GeminiErrorCode;
import sweetgirl.croissant.external.gemini.exception.GeminiException;

/**
 * Gemini generateContent 호출 — 구조화 JSON 출력 전용.
 *
 * <p>재시도는 하지 않는다. 한도 초과(429)·일시 장애(503)는 {@link GeminiException#isRetryable()} 로
 * 알려 주기만 하고, 얼마나 쉬었다 다시 보낼지는 호출자(배치 스케줄러)가 정한다 —
 * 실시간 경로(회원 메뉴 등록)와 배치 경로의 인내심이 다르기 때문.
 */
@Component
public class GeminiClient {

	private static final String GENERATE_CONTENT_PATH = "/v1beta/models/{model}:generateContent";
	private static final int TOO_MANY_REQUESTS = 429;

	private final RestClient geminiRestClient;
	private final GeminiProperties geminiProperties;
	private final ObjectMapper objectMapper;

	public GeminiClient(
			@Qualifier("geminiRestClient") RestClient geminiRestClient,
			GeminiProperties geminiProperties,
			ObjectMapper objectMapper) {
		this.geminiRestClient = geminiRestClient;
		this.geminiProperties = geminiProperties;
		this.objectMapper = objectMapper;
	}

	/**
	 * 프롬프트를 보내고 응답 JSON 을 {@code responseType} 으로 받는다. temperature 0 — 같은 입력에 같은 답을
	 * 기대하는 분류 용도라서. (그래도 갈리는 경우가 있어 호출자가 여러 번 물어 일치를 본다)
	 *
	 * @param responseSchema Gemini OpenAPI 스키마. kind 필드에 enum 을 걸어 목록 밖 값을 막는다
	 */
	public <T> GeminiStructuredResult<T> generateStructured(
			String prompt,
			Map<String, Object> responseSchema,
			Class<T> responseType) {
		return parse(
			generate(geminiProperties.getModel(), GeminiGenerateRequest.structuredJson(prompt, responseSchema, 0.0)),
			responseType);
	}

	/**
	 * 사진 한 장과 프롬프트를 함께 보낸다. 이미지는 요청 본문에 그대로 싣는다 —
	 * 한 번 보고 버릴 사진이라 파일 API 에 올렸다 지우는 왕복이 아깝다.
	 */
	public <T> GeminiStructuredResult<T> generateStructuredWithImage(
			String prompt,
			String mimeType,
			byte[] image,
			Map<String, Object> responseSchema,
			Class<T> responseType) {
		return parse(
			generate(
				geminiProperties.getModel(),
				GeminiGenerateRequest.structuredJsonWithImage(prompt, mimeType, image, responseSchema, 0.0)),
			responseType);
	}

	private <T> GeminiStructuredResult<T> parse(GeminiGenerateResponse response, Class<T> responseType) {
		String text = response.firstText()
			.orElseThrow(() -> new GeminiException(GeminiErrorCode.EMPTY_RESPONSE));
		try {
			T value = objectMapper.readValue(text, responseType);
			return new GeminiStructuredResult<>(value, response.promptTokens(), response.candidatesTokens());
		} catch (JsonProcessingException e) {
			throw new GeminiException(GeminiErrorCode.INVALID_JSON, e);
		}
	}

	/**
	 * 모델과 요청 본문을 직접 정해 부른다 — 이유 문장 실험(#662)이 한 프롬프트를 여러 모델에 돌려야 해서 열어 둔다.
	 * 파싱은 호출자 몫이다. 실험은 응답 원문도 눈으로 봐야 하기 때문.
	 */
	public GeminiGenerateResponse generate(String model, GeminiGenerateRequest request) {
		GeminiGenerateResponse response;
		try {
			response = geminiRestClient.post()
				.uri(GENERATE_CONTENT_PATH, model)
				.contentType(MediaType.APPLICATION_JSON)
				.body(request)
				.retrieve()
				.body(GeminiGenerateResponse.class);
		} catch (RestClientResponseException e) {
			throw new GeminiException(toErrorCode(e.getStatusCode()), e);
		} catch (RestClientException e) {
			throw new GeminiException(GeminiErrorCode.GENERATE_FAILED, e);
		}
		if (response == null) {
			throw new GeminiException(GeminiErrorCode.EMPTY_RESPONSE);
		}
		return response;
	}

	private GeminiErrorCode toErrorCode(HttpStatusCode status) {
		if (status.value() == TOO_MANY_REQUESTS) {
			return GeminiErrorCode.RATE_LIMITED;
		}
		if (status.is5xxServerError()) {
			return GeminiErrorCode.UNAVAILABLE;
		}
		return GeminiErrorCode.GENERATE_FAILED;
	}
}
