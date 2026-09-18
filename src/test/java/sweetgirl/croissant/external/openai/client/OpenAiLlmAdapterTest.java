package sweetgirl.croissant.external.openai.client;

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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;

import sweetgirl.croissant.external.llm.LlmProvider;
import sweetgirl.croissant.external.llm.dto.LlmRequest;
import sweetgirl.croissant.external.llm.dto.LlmResult;
import sweetgirl.croissant.external.llm.exception.LlmErrorCode;
import sweetgirl.croissant.external.llm.exception.LlmException;
import sweetgirl.croissant.external.openai.config.OpenAiProperties;

/** OpenAI Responses 어댑터 (#662) — 요청 모양·usage 이름·오류 모양. */
class OpenAiLlmAdapterTest {

	private static final String BASE_URL = "https://openai.test";
	private static final String RESPONSES_URL = BASE_URL + "/v1/responses";
	private static final Map<String, Object> SCHEMA = Map.of(
		"type", "object",
		"properties", Map.of("reason", Map.of("type", "string")),
		"required", List.of("reason"),
		"additionalProperties", false);

	private MockRestServiceServer server;
	private OpenAiProperties properties;
	private OpenAiLlmAdapter adapter;

	record Answer(String reason) {
	}

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder()
			.baseUrl(BASE_URL)
			.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer test-key");
		server = MockRestServiceServer.bindTo(builder).build();
		properties = new OpenAiProperties();
		properties.setKey("test-key");
		adapter = new OpenAiLlmAdapter(builder.build(), properties, new ObjectMapper());
	}

	private static LlmRequest request() {
		return new LlmRequest(LlmProvider.OPENAI, "gpt-5.6-luna", "이유를 써라", SCHEMA, 0.0, 800);
	}

	@Test
	@DisplayName("json_schema strict 로 보내고, 추론 항목을 건너뛰고 message 텍스트를 읽는다")
	void generateStructured_success() {
		server.expect(requestTo(RESPONSES_URL))
			.andExpect(method(HttpMethod.POST))
			.andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-key"))
			.andExpect(jsonPath("$.model").value("gpt-5.6-luna"))
			.andExpect(jsonPath("$.input").value("이유를 써라"))
			.andExpect(jsonPath("$.max_output_tokens").value(800))
			.andExpect(jsonPath("$.text.format.type").value("json_schema"))
			.andExpect(jsonPath("$.text.format.strict").value(true))
			.andExpect(jsonPath("$.text.format.schema.type").value("object"))
			.andExpect(jsonPath("$.reasoning.effort").value("low"))
			.andRespond(withSuccess(
				"""
						{"status":"completed",
						"output":[{"type":"reasoning","content":null},
						{"type":"message","content":[{"type":"output_text","text":"{\\"reason\\":\\"바삭해요\\"}"}]}],
						"usage":{"input_tokens":710,"output_tokens":48}}
						""",
				MediaType.APPLICATION_JSON));

		LlmResult<Answer> result = adapter.generateStructured(request(), Answer.class);

		assertThat(result.value().reason()).isEqualTo("바삭해요");
		assertThat(result.provider()).isEqualTo(LlmProvider.OPENAI);
		assertThat(result.inputTokens()).isEqualTo(710);
		assertThat(result.outputTokens()).isEqualTo(48);
		// 지연은 레지스트리가 잰다 — 어댑터는 0 으로 둔다
		assertThat(result.latencyMs()).isZero();
		server.verify();
	}

	@Test
	@DisplayName("추론 예산이 비면 reasoning 을 아예 안 보낸다")
	void generateStructured_omitsReasoningWhenBlank() {
		properties.setReasoningEffort("");
		server.expect(requestTo(RESPONSES_URL))
			.andExpect(jsonPath("$.reasoning").doesNotExist())
			.andRespond(withSuccess(
				"""
						{"status":"completed",
						"output":[{"type":"message","content":[
						{"type":"output_text","text":"{\\"reason\\":\\"담백해요\\"}"}]}],
						"usage":{"input_tokens":1,"output_tokens":1}}
						""",
				MediaType.APPLICATION_JSON));

		assertThat(adapter.generateStructured(request(), Answer.class).value().reason()).isEqualTo("담백해요");
		server.verify();
	}

	@Test
	@DisplayName("출력 상한에 걸려 잘리면 파싱 오류가 아니라 빈 응답으로 읽는다 — 고칠 곳이 다르다")
	void generateStructured_truncated() {
		server.expect(requestTo(RESPONSES_URL)).andRespond(withSuccess("""
				{"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},
				"output":[],"usage":{"input_tokens":710,"output_tokens":800}}
				""", MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> adapter.generateStructured(request(), Answer.class))
			.isInstanceOf(LlmException.class)
			.hasFieldOrPropertyWithValue("llmErrorCode", LlmErrorCode.EMPTY_RESPONSE);
	}

	@Test
	@DisplayName("JSON 이 아니면 INVALID_JSON")
	void generateStructured_invalidJson() {
		server.expect(requestTo(RESPONSES_URL)).andRespond(withSuccess("""
				{"status":"completed",
				"output":[{"type":"message","content":[{"type":"output_text","text":"이유: 바삭"}]}],
				"usage":{"input_tokens":1,"output_tokens":1}}
				""", MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> adapter.generateStructured(request(), Answer.class))
			.isInstanceOf(LlmException.class)
			.hasFieldOrPropertyWithValue("llmErrorCode", LlmErrorCode.INVALID_JSON);
	}

	@Test
	@DisplayName("429 는 다시 보낼 수 있는 실패로 표시한다")
	void generateStructured_rateLimited() {
		server.expect(requestTo(RESPONSES_URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

		assertThatThrownBy(() -> adapter.generateStructured(request(), Answer.class))
			.isInstanceOf(LlmException.class)
			.hasFieldOrPropertyWithValue("llmErrorCode", LlmErrorCode.RATE_LIMITED)
			.satisfies(thrown -> assertThat(((LlmException)thrown).isRetryable()).isTrue());
	}

	@Test
	@DisplayName("5xx 는 일시 장애")
	void generateStructured_unavailable() {
		server.expect(requestTo(RESPONSES_URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

		assertThatThrownBy(() -> adapter.generateStructured(request(), Answer.class))
			.isInstanceOf(LlmException.class)
			.hasFieldOrPropertyWithValue("llmErrorCode", LlmErrorCode.UNAVAILABLE);
	}

	@Test
	@DisplayName("4xx 는 우리 요청 잘못 — 다시 보내도 같다")
	void generateStructured_badRequest() {
		server.expect(requestTo(RESPONSES_URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST));

		assertThatThrownBy(() -> adapter.generateStructured(request(), Answer.class))
			.isInstanceOf(LlmException.class)
			.hasFieldOrPropertyWithValue("llmErrorCode", LlmErrorCode.GENERATE_FAILED)
			.satisfies(thrown -> assertThat(((LlmException)thrown).isRetryable()).isFalse());
	}

	@Test
	@DisplayName("키가 없으면 설정 안 된 벤더다 — 실험이 건너뛴다")
	void isConfigured_falseWithoutKey() {
		properties.setKey("  ");

		assertThat(adapter.isConfigured()).isFalse();
		assertThat(adapter.defaultModel()).isEqualTo("gpt-5.6-luna");
		assertThat(adapter.provider()).isEqualTo(LlmProvider.OPENAI);
	}
}
