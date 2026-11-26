package sweetgirl.croissant.external.llm;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;

import sweetgirl.croissant.external.anthropic.client.AnthropicLlmAdapter;
import sweetgirl.croissant.external.anthropic.config.AnthropicProperties;
import sweetgirl.croissant.external.gemini.client.GeminiClient;
import sweetgirl.croissant.external.gemini.client.GeminiLlmAdapter;
import sweetgirl.croissant.external.gemini.config.GeminiProperties;
import sweetgirl.croissant.external.llm.client.LlmClient;
import sweetgirl.croissant.external.openai.client.OpenAiLlmAdapter;
import sweetgirl.croissant.external.openai.config.OpenAiProperties;

/**
 * 진짜 LLM 을 부르는 {@code @Tag("llm")} 테스트가 쓰는 어댑터 묶음.
 *
 * <p>키는 운영과 같은 환경 변수(GEMINI_API_KEY · OPENAI_API_KEY · CLAUDE_API_KEY)에서 읽고, 키가 없는 벤더는 뺀다.
 * 스프링 컨텍스트 없이 운영 어댑터를 그대로 만든다 — 평가가 운영과 같은 요청 모양을 보내야 숫자가 의미 있다.
 */
public final class LlmTestAdapters {

	private LlmTestAdapters() {
		// 정적 도우미
	}

	public static List<LlmClient> fromEnvironment() {
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
}
