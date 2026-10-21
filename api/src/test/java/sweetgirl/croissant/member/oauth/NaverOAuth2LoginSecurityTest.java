package sweetgirl.croissant.member.oauth;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import sweetgirl.croissant.config.security.ApiAuthenticationEntryPoint;
import sweetgirl.croissant.config.security.SecurityConfig;
import sweetgirl.croissant.member.jwt.JwtProvider;
import sweetgirl.croissant.member.service.CustomOAuth2UserService;

/** 네이버 웹 OAuth의 시작·실패 리다이렉트가 빵별일지 앱 쪽으로 가는지 본다. */
@WebMvcTest(properties = "oauth2.front-redirect-uri=bbanggut://test")
@ContextConfiguration(classes = {SecurityConfig.class, ApiAuthenticationEntryPoint.class,
	NaverOAuth2LoginSecurityTest.Registrations.class})
class NaverOAuth2LoginSecurityTest {

	@MockitoBean
	JwtProvider jwtProvider;
	@MockitoBean
	CustomOAuth2UserService oauthUserService;
	@MockitoBean
	OAuth2SuccessHandler oauthSuccessHandler;
	@Autowired
	MockMvc mvc;

	@Test
	void authorizationStart_redirectsToNaverAndKeepsDeviceIdInCookie() throws Exception {
		mvc.perform(get("/oauth2/authorization/naver").param("deviceId", "device-1"))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", startsWith("https://nid.naver.com/oauth2.0/authorize")))
			.andExpect(header().string("Location", containsString("client_id=test-naver-client-id")))
			.andExpect(header().string("Location",
				containsString("redirect_uri=https://bbang-ggut.site/api/v1/oauth2/naver")))
			.andExpect(cookie().value("deviceId", "device-1"));
	}

	@Test
	void naverCallbackFailure_redirectsToBbangbyeolApp() throws Exception {
		mvc.perform(get("/api/v1/oauth2/naver").param("code", "code").param("state", "unknown-state"))
			.andExpect(redirectedUrl("daedongbbangmap://oauth?error=LOGIN_FAILED"));
	}

	@Test
	void kakaoCallbackFailure_stillRedirectsToConfiguredUri() throws Exception {
		mvc.perform(get("/api/v1/oauth2/kakao").param("code", "code").param("state", "unknown-state"))
			.andExpect(redirectedUrl("bbanggut://test?error=LOGIN_FAILED"));
	}

	@TestConfiguration
	static class Registrations {
		// 배포 설정(cd-*.yml)의 naver 등록과 같은 모양
		@Bean
		ClientRegistrationRepository clientRegistrationRepository() {
			return new InMemoryClientRegistrationRepository(ClientRegistration.withRegistrationId("naver")
				.clientId("test-naver-client-id")
				.clientSecret("test-naver-client-secret")
				.redirectUri("https://bbang-ggut.site/api/v1/oauth2/naver")
				.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
				.clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
				.clientName("Naver")
				.authorizationUri("https://nid.naver.com/oauth2.0/authorize")
				.tokenUri("https://nid.naver.com/oauth2.0/token")
				.userInfoUri("https://openapi.naver.com/v1/nid/me")
				.userNameAttributeName("response")
				.build());
		}
	}
}
