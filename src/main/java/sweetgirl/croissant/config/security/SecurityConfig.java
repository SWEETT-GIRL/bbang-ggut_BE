package sweetgirl.croissant.config.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.member.jwt.JwtAuthenticationFilter;
import sweetgirl.croissant.member.jwt.JwtProvider;
import sweetgirl.croissant.member.oauth.CustomAuthorizationRequestResolver;
import sweetgirl.croissant.member.oauth.OAuth2SuccessHandler;
import sweetgirl.croissant.member.service.CustomOAuth2UserService;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

	private final CustomOAuth2UserService customOAuth2UserService;
	private final OAuth2SuccessHandler oAuth2SuccessHandler;
	private final JwtProvider jwtProvider;

	// ClientRegistrationRepository: Spring이 application.properties의 OAuth2 설정을 읽어 자동 생성
	// CustomAuthorizationRequestResolver 생성 시 주입해서 카카오 등록 정보를 참조하게 함
	private final ClientRegistrationRepository clientRegistrationRepository;

	@Bean
	public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {

		// CSRF 비활성화
		http.csrf(csrf -> csrf.disable());

		// 세션 정책 - STATELESS
		http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

		// 요청별 접근 권한 설정
		http.authorizeHttpRequests(auth -> auth
			.requestMatchers("/oauth2/**").permitAll() // 카카오 로그인 진입점을 위해 : /oauth2/authorization/kakao
			.requestMatchers("/api/v1/oauth2/**").permitAll() // 인가 : /api/v1/oauth2/kakao
			.requestMatchers("/auth/**").permitAll() // controller 호출
			.requestMatchers("/terms").permitAll()
			.requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
			.requestMatchers("/admin/**", "/api/admin/**").permitAll().anyRequest().authenticated());
		// OAuth2 로그인 설정
		http.oauth2Login(oauth2 -> oauth2
			// deviceId 쿠키 저장을 위한 커스텀 리졸버 등록
			.authorizationEndpoint(authorization -> authorization
				.authorizationRequestResolver(
					new CustomAuthorizationRequestResolver(clientRegistrationRepository)))
			// 카카오 콜백 경로 지정
			.redirectionEndpoint(redirection -> redirection.baseUri("/api/v1/oauth2/*"))
			// 카카오 사용자 정보 수신 후 CustomOAuth2UserService 호출 (Member + OAuth upsert)
			.userInfoEndpoint(userInfo -> userInfo.userService(customOAuth2UserService))
			// 로그인 성공 시 OAuth2SuccessHandler 호출 (JWT 발급 + 딥링크 redirect)
			.successHandler(oAuth2SuccessHandler));

		// JwtAuthenticationFilter를 UsernamePasswordAuthenticationFilter 앞에 등록
		http.addFilterBefore(
			new JwtAuthenticationFilter(jwtProvider),
			UsernamePasswordAuthenticationFilter.class);

		return http.build();
	}
}
