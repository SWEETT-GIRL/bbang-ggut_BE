package sweetgirl.croissant.external.llm.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import sweetgirl.croissant.external.llm.LlmProvider;
import sweetgirl.croissant.external.llm.dto.LlmRequest;
import sweetgirl.croissant.external.llm.dto.LlmResult;
import sweetgirl.croissant.external.llm.exception.LlmErrorCode;
import sweetgirl.croissant.external.llm.exception.LlmException;

/** 레지스트리 (#662) — 지연을 한 곳에서 재고, 키 없는 벤더를 부르지 않는다. */
class LlmClientRegistryTest {

	private static final Map<String, Object> SCHEMA = Map.of("type", "object");

	record Answer(String reason) {
	}

	/** 호출마다 조금 쉬어 지연이 0 보다 크게 나오는지 본다 */
	private static final class StubClient implements LlmClient {

		private final LlmProvider provider;
		private final boolean configured;

		private StubClient(LlmProvider provider, boolean configured) {
			this.provider = provider;
			this.configured = configured;
		}

		@Override
		public LlmProvider provider() {
			return provider;
		}

		@Override
		public boolean isConfigured() {
			return configured;
		}

		@Override
		public String defaultModel() {
			return "stub-model";
		}

		@Override
		@SuppressWarnings("unchecked")
		public <T> LlmResult<T> generateStructured(LlmRequest request, Class<T> responseType) {
			try {
				Thread.sleep(5);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			return (LlmResult<T>)new LlmResult<>(new Answer("문장"), provider, request.model(), 0L, 10, 5, "{}");
		}
	}

	private static LlmRequest request(LlmProvider provider) {
		return new LlmRequest(provider, "stub-model", "이유를 써라", SCHEMA, 0.0, 800);
	}

	@Test
	@DisplayName("어댑터가 0 으로 둔 지연을 레지스트리가 재서 채운다")
	void generateStructured_measuresLatency() {
		LlmClientRegistry registry = new LlmClientRegistry(List.of(new StubClient(LlmProvider.GEMINI, true)));

		LlmResult<Answer> result = registry.generateStructured(request(LlmProvider.GEMINI), Answer.class);

		assertThat(result.latencyMs()).isPositive();
		assertThat(result.value().reason()).isEqualTo("문장");
		assertThat(result.inputTokens()).isEqualTo(10);
	}

	@Test
	@DisplayName("키가 설정된 벤더만 목록에 오른다")
	void configuredProviders_excludesUnconfigured() {
		LlmClientRegistry registry = new LlmClientRegistry(List.of(
			new StubClient(LlmProvider.GEMINI, true),
			new StubClient(LlmProvider.OPENAI, false)));

		assertThat(registry.configuredProviders()).containsExactly(LlmProvider.GEMINI);
		assertThat(registry.defaultModelOf(LlmProvider.GEMINI)).isEqualTo("stub-model");
	}

	@Test
	@DisplayName("키 없는 벤더는 부르기 전에 막는다")
	void generateStructured_rejectsUnconfiguredProvider() {
		LlmClientRegistry registry = new LlmClientRegistry(List.of(new StubClient(LlmProvider.OPENAI, false)));

		assertThatThrownBy(() -> registry.generateStructured(request(LlmProvider.OPENAI), Answer.class))
			.isInstanceOf(LlmException.class)
			.hasFieldOrPropertyWithValue("llmErrorCode", LlmErrorCode.PROVIDER_NOT_CONFIGURED);
	}

	@Test
	@DisplayName("어댑터가 아예 없는 벤더도 같은 실패로 막는다")
	void generateStructured_rejectsMissingProvider() {
		LlmClientRegistry registry = new LlmClientRegistry(List.of(new StubClient(LlmProvider.GEMINI, true)));

		assertThatThrownBy(() -> registry.generateStructured(request(LlmProvider.ANTHROPIC), Answer.class))
			.isInstanceOf(LlmException.class)
			.hasFieldOrPropertyWithValue("llmErrorCode", LlmErrorCode.PROVIDER_NOT_CONFIGURED);
	}
}
