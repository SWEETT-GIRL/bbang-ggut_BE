package sweetgirl.croissant.external.kakao.config;

import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

@Configuration
public class KakaoRestClientConfig {

	@Bean
	public RestClient kakaoRestClient(RestClient.Builder builder, KakaoProperties kakaoProperties) {
		return builder
			.baseUrl(kakaoProperties.getBaseUrl())
			.defaultHeader("Authorization", "KakaoAK " + kakaoProperties.getKey())
			.build();
	}

	/**
	 * 카카오 로그인 사용자 정보 조회(kapi)용 RestTemplate.
	 *
	 * <p>{@code new RestTemplate()}으로 직접 생성하면 공통 HTTP 클라이언트 설정
	 * ({@code spring.http.clients.*})이 적용되지 않아 타임아웃 없이 호출된다.
	 * 로그인은 사용자 대기 경로이므로 빌더를 거쳐 공통 타임아웃을 적용받게 한다.
	 */
	@Bean
	public RestTemplate kakaoAuthRestTemplate(RestTemplateBuilder builder) {
		return builder.build();
	}
}
