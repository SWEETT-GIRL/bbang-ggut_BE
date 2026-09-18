package sweetgirl.croissant.external.openai.config;

import java.net.http.HttpClient;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class OpenAiRestClientConfig {

	private static final String BEARER_PREFIX = "Bearer ";

	/**
	 * 공통 RestClient.Builder 의 읽기 3초를 갈아 끼운다 — 이유 문장 생성은 실측 수 초가 걸려 공통 값으로는
	 * 정상 응답도 타임아웃이다. Gemini 전용 클라이언트와 같은 이유·같은 모양.
	 */
	@Bean
	public RestClient openAiRestClient(RestClient.Builder builder, OpenAiProperties openAiProperties) {
		HttpClient httpClient = HttpClient.newBuilder()
			.connectTimeout(openAiProperties.getConnectTimeout())
			.build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(openAiProperties.getReadTimeout());
		return builder
			.baseUrl(openAiProperties.getBaseUrl())
			.defaultHeader(HttpHeaders.AUTHORIZATION, BEARER_PREFIX + openAiProperties.getKey())
			.requestFactory(requestFactory)
			.build();
	}
}
