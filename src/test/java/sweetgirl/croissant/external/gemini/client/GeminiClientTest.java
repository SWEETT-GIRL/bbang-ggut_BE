package sweetgirl.croissant.external.gemini.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;

import sweetgirl.croissant.external.gemini.config.GeminiProperties;
import sweetgirl.croissant.external.gemini.dto.GeminiStructuredResult;
import sweetgirl.croissant.external.gemini.exception.GeminiErrorCode;
import sweetgirl.croissant.external.gemini.exception.GeminiException;

class GeminiClientTest {

	private static final String BASE_URL = "https://gemini.test";
	private static final String GENERATE_URL = BASE_URL + "/v1beta/models/gemini-3.5-flash-lite:generateContent";
	private static final Map<String, Object> SCHEMA = Map.of("type", "OBJECT");

	private MockRestServiceServer server;
	private GeminiClient geminiClient;

	record Answer(String kind, boolean isBakeryItem) {
	}

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder()
			.baseUrl(BASE_URL)
			.defaultHeader("x-goog-api-key", "test-key");
		server = MockRestServiceServer.bindTo(builder).build();
		GeminiProperties properties = new GeminiProperties();
		properties.setKey("test-key");
		geminiClient = new GeminiClient(builder.build(), properties, new ObjectMapper());
	}

	@Nested
	@DisplayName("구조화 출력 호출")
	class GenerateStructured {

		@Test
		@DisplayName("모델 경로·키 헤더·JSON 스키마를 실어 보내고, 응답 텍스트를 요청 타입으로 돌려준다")
		void success() {
			server.expect(requestTo(GENERATE_URL))
				.andExpect(method(HttpMethod.POST))
				.andExpect(header("x-goog-api-key", "test-key"))
				.andExpect(jsonPath("$.contents[0].parts[0].text").value("소금빵을 분류해라"))
				.andExpect(jsonPath("$.generationConfig.responseMimeType").value("application/json"))
				.andExpect(jsonPath("$.generationConfig.responseSchema.type").value("OBJECT"))
				.andExpect(jsonPath("$.generationConfig.temperature").value(0.0))
				.andRespond(withSuccess(
					"{\"candidates\": [{\"content\": {\"parts\": [{\"text\": "
						+ "\"{\\\"kind\\\":\\\"소금빵\\\",\\\"isBakeryItem\\\":true}\"}]}}],"
						+ " \"usageMetadata\": {\"promptTokenCount\": 12, \"candidatesTokenCount\": 7}}",
					MediaType.APPLICATION_JSON));

			GeminiStructuredResult<Answer> result = geminiClient.generateStructured("소금빵을 분류해라", SCHEMA, Answer.class);

			assertThat(result.value().kind()).isEqualTo("소금빵");
			assertThat(result.value().isBakeryItem()).isTrue();
			assertThat(result.promptTokens()).isEqualTo(12);
			assertThat(result.candidatesTokens()).isEqualTo(7);
			server.verify();
		}

		@Test
		@DisplayName("후보가 비어 있으면 EMPTY_RESPONSE")
		void emptyCandidates() {
			server.expect(requestTo(GENERATE_URL))
				.andRespond(withSuccess("{\"candidates\": []}", MediaType.APPLICATION_JSON));

			assertThatThrownBy(() -> geminiClient.generateStructured("p", SCHEMA, Answer.class))
				.isInstanceOf(GeminiException.class)
				.extracting("geminiErrorCode").isEqualTo(GeminiErrorCode.EMPTY_RESPONSE);
		}

		@Test
		@DisplayName("응답 텍스트가 JSON 이 아니면 INVALID_JSON")
		void notJson() {
			server.expect(requestTo(GENERATE_URL))
				.andRespond(withSuccess(
					"{\"candidates\": [{\"content\": {\"parts\": [{\"text\": \"소금빵입니다\"}]}}]}",
					MediaType.APPLICATION_JSON));

			assertThatThrownBy(() -> geminiClient.generateStructured("p", SCHEMA, Answer.class))
				.isInstanceOf(GeminiException.class)
				.extracting("geminiErrorCode").isEqualTo(GeminiErrorCode.INVALID_JSON);
		}
	}

	@Nested
	@DisplayName("HTTP 오류 → 재시도 가능 여부가 갈린다")
	class HttpErrors {

		@Test
		@DisplayName("429 는 RATE_LIMITED, 재시도 가능")
		void rateLimited() {
			server.expect(requestTo(GENERATE_URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

			assertThatThrownBy(() -> geminiClient.generateStructured("p", SCHEMA, Answer.class))
				.isInstanceOfSatisfying(GeminiException.class, exception -> {
					assertThat(exception.getGeminiErrorCode()).isEqualTo(GeminiErrorCode.RATE_LIMITED);
					assertThat(exception.isRetryable()).isTrue();
				});
		}

		@Test
		@DisplayName("503 은 UNAVAILABLE, 재시도 가능")
		void unavailable() {
			server.expect(requestTo(GENERATE_URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

			assertThatThrownBy(() -> geminiClient.generateStructured("p", SCHEMA, Answer.class))
				.isInstanceOfSatisfying(GeminiException.class, exception -> {
					assertThat(exception.getGeminiErrorCode()).isEqualTo(GeminiErrorCode.UNAVAILABLE);
					assertThat(exception.isRetryable()).isTrue();
				});
		}

		@Test
		@DisplayName("400 은 GENERATE_FAILED, 재시도 불가 — 요청 자체가 잘못됐으니 다시 보내도 같다")
		void badRequest() {
			server.expect(requestTo(GENERATE_URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST));

			assertThatThrownBy(() -> geminiClient.generateStructured("p", SCHEMA, Answer.class))
				.isInstanceOfSatisfying(GeminiException.class, exception -> {
					assertThat(exception.getGeminiErrorCode()).isEqualTo(GeminiErrorCode.GENERATE_FAILED);
					assertThat(exception.isRetryable()).isFalse();
				});
		}
	}
}
