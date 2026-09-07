package sweetgirl.croissant.external.gemini.config;

import java.net.http.HttpClient;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class GeminiRestClientConfig {

	/** API 키는 쿼리(?key=)가 아니라 헤더로 — 접근 로그·예외 메시지의 URL 에 키가 남지 않게 */
	static final String API_KEY_HEADER = "x-goog-api-key";

	/**
	 * 공통 RestClient.Builder 의 요청 팩토리(읽기 3초)를 Gemini 전용으로 갈아 끼운다.
	 * 메뉴 100건 배치 분류가 실측 4~20초라 공통 값으로는 정상 응답도 타임아웃이다.
	 */
	@Bean
	public RestClient geminiRestClient(RestClient.Builder builder, GeminiProperties geminiProperties) {
		HttpClient httpClient = HttpClient.newBuilder()
			.connectTimeout(geminiProperties.getConnectTimeout())
			.build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(geminiProperties.getReadTimeout());
		return builder
			.baseUrl(geminiProperties.getBaseUrl())
			.defaultHeader(API_KEY_HEADER, geminiProperties.getKey())
			.requestFactory(requestFactory)
			.build();
	}
}
