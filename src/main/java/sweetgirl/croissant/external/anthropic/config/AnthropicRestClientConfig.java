package sweetgirl.croissant.external.anthropic.config;

import java.net.http.HttpClient;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class AnthropicRestClientConfig {

	/** 키는 쿼리가 아니라 헤더로 — 접근 로그·예외 메시지의 URL 에 키가 남지 않게 */
	static final String API_KEY_HEADER = "x-api-key";

	static final String VERSION_HEADER = "anthropic-version";

	/** 공통 RestClient.Builder 의 읽기 3초를 갈아 끼운다 — 이유 문장 생성은 실측 수 초가 걸린다 */
	@Bean
	public RestClient anthropicRestClient(RestClient.Builder builder, AnthropicProperties anthropicProperties) {
		HttpClient httpClient = HttpClient.newBuilder()
			.connectTimeout(anthropicProperties.getConnectTimeout())
			.build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(anthropicProperties.getReadTimeout());
		return builder
			.baseUrl(anthropicProperties.getBaseUrl())
			.defaultHeader(API_KEY_HEADER, anthropicProperties.getKey())
			.defaultHeader(VERSION_HEADER, anthropicProperties.getVersion())
			.requestFactory(requestFactory)
			.build();
	}
}
