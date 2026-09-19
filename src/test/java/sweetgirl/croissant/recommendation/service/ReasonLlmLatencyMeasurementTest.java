package sweetgirl.croissant.recommendation.service;

import static org.assertj.core.api.Assumptions.assumeThat;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;

import sweetgirl.croissant.external.anthropic.client.AnthropicLlmAdapter;
import sweetgirl.croissant.external.anthropic.config.AnthropicProperties;
import sweetgirl.croissant.external.gemini.client.GeminiClient;
import sweetgirl.croissant.external.gemini.client.GeminiLlmAdapter;
import sweetgirl.croissant.external.gemini.config.GeminiProperties;
import sweetgirl.croissant.external.llm.LlmProvider;
import sweetgirl.croissant.external.llm.client.LlmClient;
import sweetgirl.croissant.external.llm.client.LlmClientRegistry;
import sweetgirl.croissant.external.llm.dto.LlmRequest;
import sweetgirl.croissant.external.llm.dto.LlmResult;
import sweetgirl.croissant.external.llm.exception.LlmException;
import sweetgirl.croissant.external.openai.client.OpenAiLlmAdapter;
import sweetgirl.croissant.external.openai.config.OpenAiProperties;
import sweetgirl.croissant.recommendation.config.RecommendationProperties;
import sweetgirl.croissant.recommendation.enums.ReasonPromptVariant;
import sweetgirl.croissant.recommendation.repository.CandidateStoreRow;
import sweetgirl.croissant.recommendation.repository.TasteQuery;
import sweetgirl.croissant.recommendation.service.ReasonExperimentService.Reason;
import sweetgirl.croissant.recommendation.service.ReasonExperimentService.ReasonAnswer;

/**
 * 진짜 LLM 을 불러 <b>얼마나 걸리는지</b> 재는 측정 테스트 (#662). 기본 빌드에서 제외된다 — 호출마다 실제 비용이 나간다.
 *
 * <pre>
 * GEMINI_API_KEY=… OPENAI_API_KEY=… CLAUDE_API_KEY=… \
 *   ./gradlew test -PllmTests --tests "*ReasonLlmLatencyMeasurementTest" -i
 * </pre>
 *
 * <p>DB 를 타지 않는다. 입력은 아래 고정 한 판이라 모델을 바꿔도 같은 프롬프트가 나가고, 그래야 지연 비교가 성립한다.
 * 키가 없는 벤더는 조용히 건너뛴다.
 */
@Tag("llm")
class ReasonLlmLatencyMeasurementTest {

	private static final int MAX_OUTPUT_TOKENS = 800;
	private static final Map<String, Object> RESPONSE_SCHEMA = Map.of(
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

	/** 판: 09-18 기준 후보 셋. 여기 한 줄을 더하면 모델이 는다 (Sonnet 5 는 24초라 뺐다) */
	private static final List<String> MODELS = List.of(
		"GEMINI:gemini-3.5-flash-lite",
		"ANTHROPIC:claude-haiku-4-5-20251001",
		"OPENAI:gpt-5.6-luna");

	@Test
	@DisplayName("모델 × 변형마다 이유 네 줄을 받아 지연·토큰·문장을 표로 남긴다")
	void measure() {
		List<LlmClient> clients = adapters();
		assumeThat(clients).as("LLM 키가 하나도 없다 — 환경 변수로 키를 주고 돌린다").isNotEmpty();
		LlmClientRegistry registry = new LlmClientRegistry(clients);
		List<LlmProvider> configured = registry.configuredProviders();

		ReasonPromptBuilder promptBuilder = new ReasonPromptBuilder(new RecommendationProperties());
		List<CandidateStoreRow> stores = stores();

		System.out.println("조합 | 지연(ms) | 입력토큰 | 출력토큰 | 문장");
		for (String model : MODELS) {
			String[] parts = model.split(":", 2);
			LlmProvider provider = LlmProvider.valueOf(parts[0]);
			if (!configured.contains(provider)) {
				System.out.printf(Locale.ROOT, "%s | 건너뜀(키 없음)%n", model);
				continue;
			}
			for (ReasonPromptVariant variant : List.of(ReasonPromptVariant.values())) {
				String prompt = promptBuilder.build(variant, taste(), stores, SIGNATURE_MENUS);
				measureOne(registry, provider, parts[1], variant, prompt);
			}
		}
	}

	private void measureOne(LlmClientRegistry registry, LlmProvider provider, String model,
			ReasonPromptVariant variant, String prompt) {
		String label = provider + "/" + model + "/" + variant;
		try {
			LlmResult<ReasonAnswer> result = registry.generateStructured(
				new LlmRequest(provider, model, prompt, RESPONSE_SCHEMA, 0.0, MAX_OUTPUT_TOKENS), ReasonAnswer.class);
			List<Reason> reasons = result.value().reasons() == null ? List.of() : result.value().reasons();
			System.out.printf(Locale.ROOT, "%s | %d | %d | %d | %s%n",
				label, result.latencyMs(), result.inputTokens(), result.outputTokens(),
				reasons.stream().map(Reason::reason).toList());
		} catch (LlmException e) {
			System.out.printf(Locale.ROOT, "%s | 실패 %s (%s)%n", label, e.getLlmErrorCode().getCode(), e.getMessage());
		}
	}

	/** 환경 변수에 키가 있는 벤더만 만든다 — 없는 벤더는 registry 가 "설정 안 됨"으로 막는다 */
	private List<LlmClient> adapters() {
		ObjectMapper objectMapper = new ObjectMapper();
		List<LlmClient> clients = new ArrayList<>();

		GeminiProperties geminiProperties = new GeminiProperties();
		geminiProperties.setKey(System.getenv("GEMINI_API_KEY"));
		RestClient geminiRestClient = restClient(geminiProperties.getBaseUrl(), "x-goog-api-key",
			geminiProperties.getKey(), geminiProperties.getConnectTimeout(), geminiProperties.getReadTimeout());
		clients.add(new GeminiLlmAdapter(
			new GeminiClient(geminiRestClient, geminiProperties, objectMapper), geminiProperties, objectMapper));

		OpenAiProperties openAiProperties = new OpenAiProperties();
		openAiProperties.setKey(System.getenv("OPENAI_API_KEY"));
		RestClient openAiRestClient = restClient(openAiProperties.getBaseUrl(), HttpHeaders.AUTHORIZATION,
			"Bearer " + openAiProperties.getKey(), openAiProperties.getConnectTimeout(),
			openAiProperties.getReadTimeout());
		clients.add(new OpenAiLlmAdapter(openAiRestClient, openAiProperties, objectMapper));

		AnthropicProperties anthropicProperties = new AnthropicProperties();
		anthropicProperties.setKey(System.getenv("CLAUDE_API_KEY"));
		RestClient anthropicRestClient = RestClient.builder()
			.baseUrl(anthropicProperties.getBaseUrl())
			.defaultHeader("x-api-key", String.valueOf(anthropicProperties.getKey()))
			.defaultHeader("anthropic-version", anthropicProperties.getVersion())
			.requestFactory(requestFactory(anthropicProperties.getConnectTimeout(),
				anthropicProperties.getReadTimeout()))
			.build();
		clients.add(new AnthropicLlmAdapter(anthropicRestClient, anthropicProperties, objectMapper));

		return clients.stream().filter(LlmClient::isConfigured).toList();
	}

	private static RestClient restClient(String baseUrl, String headerName, String headerValue,
			Duration connectTimeout, Duration readTimeout) {
		return RestClient.builder()
			.baseUrl(baseUrl)
			.defaultHeader(headerName, String.valueOf(headerValue))
			.requestFactory(requestFactory(connectTimeout, readTimeout))
			.build();
	}

	private static JdkClientHttpRequestFactory requestFactory(Duration connectTimeout, Duration readTimeout) {
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
			HttpClient.newBuilder().connectTimeout(connectTimeout).build());
		requestFactory.setReadTimeout(readTimeout);
		return requestFactory;
	}

	/** 변형 B 가 쓰는 시그니처 메뉴. 고정 판이라 여기도 손으로 적어 둔다 */
	private static final Map<Long, List<String>> SIGNATURE_MENUS = Map.of(
		1L, List.of("소금빵", "깜빠뉴", "바게트"),
		2L, List.of("크루아상", "스콘"),
		3L, List.of("단팥빵", "소보로"),
		4L, List.of("바게트", "치아바타", "사워도우"));

	/** 담백·바삭·심플·트렌디 쪽 회원 — 뉴웨이브 미니멀(PCLN) */
	private static TasteQuery taste() {
		return new TasteQuery(0.62, 0.41, 0.38, 0.22,
			Map.of("특별한메뉴", 0.9, "건강한맛", 0.5, "웨이팅", 0.3), List.of("소금빵", "바게트", "깜빠뉴"), 4);
	}

	private static List<CandidateStoreRow> stores() {
		return List.of(
			store(1L, "밀도", 0.71, 3.9, 0.55, 0.72, 0.30, 0.48,
				Map.of("특별한메뉴", 1.0, "건강한맛", 0.8), List.of("소금빵", "깜빠뉴")),
			store(2L, "베이커리 성수", 0.44, 3.1, 0.20, 0.35, -0.10, 0.60,
				Map.of("머물기", 1.0, "공간사진", 0.7), List.of("크루아상", "스콘")),
			store(3L, "동네 방앗간빵집", 0.18, 2.2, -0.30, -0.20, -0.45, -0.70,
				Map.of("디저트", 1.0, "특별한메뉴", 0.9), List.of("단팥빵", "소보로")),
			store(4L, "브레드랩", 0.63, 2.8, 0.48, 0.66, 0.41, 0.35,
				Map.of("웨이팅", 1.0, "건강한맛", 0.6), List.of("바게트", "치아바타")));
	}

	private static CandidateStoreRow store(long storeId, String name, double tasteFit, double popularityScore,
			double axisTaste, double axisTexture, double axisFilling, double axisVibe,
			Map<String, Double> tags, List<String> kinds) {
		return new CandidateStoreRow(storeId, name, "성수동2가", 37.5446, 127.0557, null, 200, popularityScore,
			1.2, tasteFit, 5, axisTaste, axisTexture, axisFilling, axisVibe, tags, kinds);
	}
}
