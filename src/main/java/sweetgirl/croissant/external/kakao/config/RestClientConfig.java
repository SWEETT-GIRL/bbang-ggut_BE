package sweetgirl.croissant.external.kakao.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

	@Bean
	public RestClient kakaoRestClient(KakaoProperties kakaoProperties) {
		return RestClient.builder()
			.baseUrl(kakaoProperties.getBaseUrl())
			.defaultHeader("Authorization", "KakaoAK " + kakaoProperties.getKey())
			.build();
	}
}
