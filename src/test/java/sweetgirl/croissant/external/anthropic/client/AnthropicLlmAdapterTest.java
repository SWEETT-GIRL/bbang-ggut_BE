package sweetgirl.croissant.external.anthropic.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;

import sweetgirl.croissant.external.anthropic.config.AnthropicProperties;
import sweetgirl.croissant.external.llm.LlmProvider;
import sweetgirl.croissant.external.llm.dto.LlmRequest;
import sweetgirl.croissant.external.llm.dto.LlmResult;
import sweetgirl.croissant.external.llm.exception.LlmErrorCode;
import sweetgirl.croissant.external.llm.exception.LlmException;

/** Anthropic Messages 어댑터 (#662) — 여기만 거부가 HTTP 200 에 담겨 온다. */
class AnthropicLlmAdapterTest {

	private static final String BASE_URL = "https://anthropic.test";
	private static final String MESSAGES_URL = BASE_URL + "/v1/messages";
	private static final Map<String, Object> SCHEMA = Map.of(
		"type", "object",
		"properties", Map.of("reason", Map.of("type", "string")),
		"required", List.of("reason"),
		"additionalProperties", false);

	private MockRestServiceServer server;
	private AnthropicProperties properties;
	private AnthropicLlmAdapter adapter;

	record Answer(String reason) {
	}

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder()
			.baseUrl(BASE_URL)
			.defaultHeader("x-api-key", "test-key")
			.defaultHeader("anthropic-version", "2023-06-01");
		server = MockRestServiceServer.bindTo(builder).build();
		properties = new AnthropicProperties();
		properties.setKey("test-key");
		adapter = new AnthropicLlmAdapter(builder.build(), properties, new ObjectMapper());
	}

	private static LlmRequest request() {
		return new LlmRequest(LlmProvider.ANTHROPIC, "claude-haiku-4-5-20251001", "이유를 써라", SCHEMA, 0.0, 800);
	}

	@Test
	@DisplayName("output_config 로 스키마를 걸고, 첫 텍스트 블록의 JSON 을 읽는다")
	void generateStructured_success() {
		server.expect(requestTo(MESSAGES_URL))
			.andExpect(method(HttpMethod.POST))
			.andExpect(header("x-api-key", "test-key"))
			.andExpect(header("anthropic-version", "2023-06-01"))
			.andExpect(jsonPath("$.model").value("claude-haiku-4-5-20251001"))
			.andExpect(jsonPath("$.max_tokens").value(800))
			.andExpect(jsonPath("$.temperature").value(0.0))
			.andExpect(jsonPath("$.messages[0].role").value("user"))
			.andExpect(jsonPath("$.messages[0].content").value("이유를 써라"))
			.andExpect(jsonPath("$.output_config.format.type").value("json_schema"))
			.andExpect(jsonPath("$.output_config.format.schema.additionalProperties").value(false))
			.andRespond(withSuccess("""
					{"content":[{"type":"text","text":"{\\"reason\\":\\"담백해요\\"}"}],
					"stop_reason":"end_turn",
					"usage":{"input_tokens":690,"output_tokens":52}}
					""", MediaType.APPLICATION_JSON));

		LlmResult<Answer> result = adapter.generateStructured(request(), Answer.class);

		assertThat(result.value().reason()).isEqualTo("담백해요");
		assertThat(result.provider()).isEqualTo(LlmProvider.ANTHROPIC);
		assertThat(result.inputTokens()).isEqualTo(690);
		assertThat(result.outputTokens()).isEqualTo(52);
		assertThat(result.latencyMs()).isZero();
		server.verify();
	}

	@Test
	@DisplayName("거부는 200 으로 온다 — 본문을 보고 알아야 한다")
	void generateStructured_refusal() {
		server.expect(requestTo(MESSAGES_URL)).andRespond(withSuccess("""
				{"content":[],"stop_reason":"refusal","usage":{"input_tokens":690,"output_tokens":0}}
				""", MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> adapter.generateStructured(request(), Answer.class))
			.isInstanceOf(LlmException.class)
			.hasFieldOrPropertyWithValue("llmErrorCode", LlmErrorCode.REFUSED)
			.satisfies(thrown -> assertThat(((LlmException)thrown).isRetryable()).isFalse());
	}

	@Test
	@DisplayName("출력 상한에 걸려 잘리면 빈 응답으로 읽는다")
	void generateStructured_truncated() {
		server.expect(requestTo(MESSAGES_URL)).andRespond(withSuccess("""
				{"content":[{"type":"text","text":"{\\"reason\\":\\"담백"}],
				"stop_reason":"max_tokens","usage":{"input_tokens":690,"output_tokens":800}}
				""", MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> adapter.generateStructured(request(), Answer.class))
			.isInstanceOf(LlmException.class)
			.hasFieldOrPropertyWithValue("llmErrorCode", LlmErrorCode.EMPTY_RESPONSE);
	}

	@Test
	@DisplayName("429 는 다시 보낼 수 있는 실패")
	void generateStructured_rateLimited() {
		server.expect(requestTo(MESSAGES_URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

		assertThatThrownBy(() -> adapter.generateStructured(request(), Answer.class))
			.isInstanceOf(LlmException.class)
			.hasFieldOrPropertyWithValue("llmErrorCode", LlmErrorCode.RATE_LIMITED);
	}

	@Test
	@DisplayName("529(과부하)도 일시 장애로 본다 — 여기만 쓰는 코드다")
	void generateStructured_overloaded() {
		server.expect(requestTo(MESSAGES_URL)).andRespond(withStatus(HttpStatusCode.valueOf(529)));

		assertThatThrownBy(() -> adapter.generateStructured(request(), Answer.class))
			.isInstanceOf(LlmException.class)
			.hasFieldOrPropertyWithValue("llmErrorCode", LlmErrorCode.UNAVAILABLE)
			.satisfies(thrown -> assertThat(((LlmException)thrown).isRetryable()).isTrue());
	}

	@Test
	@DisplayName("키가 없으면 설정 안 된 벤더다")
	void isConfigured_falseWithoutKey() {
		properties.setKey(null);

		assertThat(adapter.isConfigured()).isFalse();
		assertThat(adapter.provider()).isEqualTo(LlmProvider.ANTHROPIC);
		assertThat(adapter.defaultModel()).isEqualTo("claude-haiku-4-5-20251001");
	}
}
