package sweetgirl.croissant.external.kakao.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class KakaoRestClientConfig {

	@Bean
	public RestClient kakaoRestClient(RestClient.Builder builder, KakaoProperties kakaoProperties) {
		return builder
			.baseUrl(kakaoProperties.getBaseUrl())
			.defaultHeader("Authorization", "KakaoAK " + kakaoProperties.getKey())
			.build();
	}
}
