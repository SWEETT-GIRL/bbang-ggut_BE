package sweetgirl.croissant.external.gemini.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.ObjectMapper;

import sweetgirl.croissant.external.gemini.config.GeminiProperties;
import sweetgirl.croissant.external.gemini.dto.GeminiGenerateRequest;
import sweetgirl.croissant.external.gemini.dto.GeminiGenerateResponse;
import sweetgirl.croissant.external.gemini.exception.GeminiErrorCode;
import sweetgirl.croissant.external.gemini.exception.GeminiException;
import sweetgirl.croissant.external.llm.LlmProvider;
import sweetgirl.croissant.external.llm.dto.LlmRequest;
import sweetgirl.croissant.external.llm.dto.LlmResult;
import sweetgirl.croissant.external.llm.exception.LlmErrorCode;
import sweetgirl.croissant.external.llm.exception.LlmException;

/** Gemini 어댑터 (#662) — 표준 JSON 스키마를 Gemini 가 받는 모양으로 옮기는 자리. */
@ExtendWith(MockitoExtension.class)
class GeminiLlmAdapterTest {

	/** 셋 중 Gemini 만 못 받는 것들이 다 들어 있는 스키마 — 소문자 타입 · additionalProperties · 중첩 배열 */
	private static final Map<String, Object> SCHEMA = Map.of(
		"type", "object",
		"properties", Map.of(
			"reasons", Map.of(
				"type", "array",
				"items", Map.of(
					"type", "object",
					"properties", Map.of(
						"storeId", Map.of("type", "integer"),
						"reason", Map.of("type", "string")),
					"required", List.of("storeId", "reason"),
					"additionalProperties", false))),
		"required", List.of("reasons"),
		"additionalProperties", false);

	@Mock
	GeminiClient geminiClient;

	GeminiProperties properties = new GeminiProperties();
	GeminiLlmAdapter adapter;

	record Answer(String reason) {
	}

	@BeforeEach
	void setUp() {
		properties.setKey("test-key");
		adapter = new GeminiLlmAdapter(geminiClient, properties, new ObjectMapper());
	}

	private static LlmRequest request() {
		return new LlmRequest(LlmProvider.GEMINI, "gemini-3.5-flash-lite", "이유를 써라", SCHEMA, 0.0, 800);
	}

	private static GeminiGenerateResponse response(String text) {
		return new GeminiGenerateResponse(
			List.of(new GeminiGenerateResponse.Candidate(
				new GeminiGenerateResponse.Content(List.of(new GeminiGenerateResponse.Part(text))), "STOP")),
			new GeminiGenerateResponse.UsageMetadata(700, 45));
	}

	@Test
	@DisplayName("타입을 대문자로 바꾸고 additionalProperties 를 뺀다 — 두면 400 이 난다")
	@SuppressWarnings("unchecked")
	void generateStructured_translatesSchema() {
		given(geminiClient.generate(eq("gemini-3.5-flash-lite"), any()))
			.willReturn(response("{\"reason\":\"바삭해요\"}"));

		LlmResult<Answer> result = adapter.generateStructured(request(), Answer.class);

		ArgumentCaptor<GeminiGenerateRequest> captor = ArgumentCaptor.forClass(GeminiGenerateRequest.class);
		verify(geminiClient).generate(eq("gemini-3.5-flash-lite"), captor.capture());
		Map<String, Object> schema = captor.getValue().generationConfig().responseSchema();

		assertThat(schema.get("type")).isEqualTo("OBJECT");
		assertThat(schema).doesNotContainKey("additionalProperties");
		Map<String, Object> reasons = (Map<String, Object>)((Map<String, Object>)schema.get("properties"))
			.get("reasons");
		assertThat(reasons.get("type")).isEqualTo("ARRAY");
		Map<String, Object> item = (Map<String, Object>)reasons.get("items");
		assertThat(item.get("type")).isEqualTo("OBJECT");
		assertThat(item).doesNotContainKey("additionalProperties");
		assertThat(item.get("required")).isEqualTo(List.of("storeId", "reason"));
		Map<String, Object> storeId = (Map<String, Object>)((Map<String, Object>)item.get("properties")).get("storeId");
		assertThat(storeId.get("type")).isEqualTo("INTEGER");

		assertThat(captor.getValue().generationConfig().maxOutputTokens()).isEqualTo(800);
		assertThat(result.value().reason()).isEqualTo("바삭해요");
		assertThat(result.inputTokens()).isEqualTo(700);
		assertThat(result.outputTokens()).isEqualTo(45);
		assertThat(result.latencyMs()).isZero();
	}

	@Test
	@DisplayName("Gemini 오류 코드를 공통 코드로 옮긴다")
	void generateStructured_mapsErrorCode() {
		given(geminiClient.generate(any(), any())).willThrow(new GeminiException(GeminiErrorCode.RATE_LIMITED));

		assertThatThrownBy(() -> adapter.generateStructured(request(), Answer.class))
			.isInstanceOf(LlmException.class)
			.hasFieldOrPropertyWithValue("llmErrorCode", LlmErrorCode.RATE_LIMITED)
			.satisfies(thrown -> assertThat(((LlmException)thrown).isRetryable()).isTrue());
	}

	@Test
	@DisplayName("빈 텍스트는 빈 응답으로 읽는다")
	void generateStructured_emptyText() {
		given(geminiClient.generate(any(), any())).willReturn(response("  "));

		assertThatThrownBy(() -> adapter.generateStructured(request(), Answer.class))
			.isInstanceOf(LlmException.class)
			.hasFieldOrPropertyWithValue("llmErrorCode", LlmErrorCode.EMPTY_RESPONSE);
	}

	@Test
	@DisplayName("JSON 이 아니면 INVALID_JSON")
	void generateStructured_invalidJson() {
		given(geminiClient.generate(any(), any())).willReturn(response("이유: 바삭"));

		assertThatThrownBy(() -> adapter.generateStructured(request(), Answer.class))
			.isInstanceOf(LlmException.class)
			.hasFieldOrPropertyWithValue("llmErrorCode", LlmErrorCode.INVALID_JSON);
	}

	@Test
	@DisplayName("키가 없으면 설정 안 된 벤더다")
	void isConfigured_falseWithoutKey() {
		properties.setKey("");

		assertThat(adapter.isConfigured()).isFalse();
		assertThat(adapter.provider()).isEqualTo(LlmProvider.GEMINI);
		assertThat(adapter.defaultModel()).isEqualTo("gemini-3.5-flash-lite");
	}
}
