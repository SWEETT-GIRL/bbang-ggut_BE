package sweetgirl.croissant.config.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.util.matcher.RegexRequestMatcher;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.member.jwt.JwtAuthenticationFilter;
import sweetgirl.croissant.member.jwt.JwtProvider;
import sweetgirl.croissant.member.oauth.CustomAuthorizationRequestResolver;
import sweetgirl.croissant.member.oauth.OAuth2SuccessHandler;
import sweetgirl.croissant.member.service.CustomOAuth2UserService;

@Slf4j
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

	private final CustomOAuth2UserService customOAuth2UserService;
	private final OAuth2SuccessHandler oAuth2SuccessHandler;
	private final JwtProvider jwtProvider;
	private final ApiAuthenticationEntryPoint apiAuthenticationEntryPoint;

	// ClientRegistrationRepository: Spring이 application.properties의 OAuth2 설정을 읽어 자동 생성
	// CustomAuthorizationRequestResolver 생성 시 주입해서 카카오 등록 정보를 참조하게 함
	private final ClientRegistrationRepository clientRegistrationRepository;

	@Value("${oauth2.front-redirect-uri}")
	private String frontRedirectUri;

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
			// 앱 시작 시 로그인 전에 부른다 — 구버전 차단 판단은 토큰보다 먼저여야 한다
			.requestMatchers(HttpMethod.GET, "/app/version-policy").permitAll()
			.requestMatchers(HttpMethod.PATCH, "/callback/images/status").permitAll()
			.requestMatchers(HttpMethod.GET, "/dev/**").permitAll()
			// actuator는 management.server.port(8081)로 분리되어 있으나 요청은 이 필터체인을 거친다.
			// prometheus는 스크랩 주체가 인증 정보를 붙이지 않으므로 인증을 면제한다.
			// health 하위 그룹(readiness/liveness)도 열어둔다 — 배포 파이프라인과 ALB가
			// /actuator/health/readiness로 트래픽 투입 시점을 판단한다.
			.requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/metrics",
				"/actuator/metrics/**", "/actuator/prometheus")
			.permitAll()
			.requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
			.requestMatchers(HttpMethod.GET, "/trend/**", "/menu-categories", "/banners", "/banners/**").permitAll()
			// 지도의 '요즘 뜨는 디저트'는 회원과 무관한 전역 콘텐츠다 — 지도는 로그인 전에도 보인다
			.requestMatchers(HttpMethod.GET, "/desserts", "/desserts/**").permitAll()
			// 세계관 카탈로그는 회원과 무관한 전역 콘텐츠다
			.requestMatchers(HttpMethod.GET, "/world/catalog").permitAll()
			.requestMatchers(HttpMethod.GET, "/onboarding/bbangdaengi-v1.png").permitAll()
			// 빵 종류 목록·평가 칩 목록은 회원과 무관한 전역 콘텐츠다
			.requestMatchers(HttpMethod.GET, "/bread-categories", "/bread-rating-chips", "/bread-rankings/categories")
			.permitAll()
			// AdMob 보상 검증은 구글이 부르는 자리다. 인증 대신 서명으로 진짜인지를 가린다
			.requestMatchers(HttpMethod.GET, "/ads/rewarded/ssv").permitAll()
			// 홈화면 근처/인기 빵집·검색 비로그인 허용
			.requestMatchers(HttpMethod.GET, "/stores/district", "/stores/nearby", "/stores/popular",
				"/stores/search",
				"/stores/autocomplete",
				"/stores/in-bounds")
			.permitAll()
			// 공개 코스 목록 비로그인 허용
			.requestMatchers(HttpMethod.GET, "/courses").permitAll()
			// 숫자 ID 기반 빵집 상세/메뉴/리뷰·코스 상세 조회 비로그인 허용
			.requestMatchers(
				RegexRequestMatcher.regexMatcher(HttpMethod.GET, "^/stores/\\d+(\\?.*)?$"),
				RegexRequestMatcher.regexMatcher(HttpMethod.GET, "^/stores/\\d+/menus(\\?.*)?$"),
				RegexRequestMatcher.regexMatcher(HttpMethod.GET, "^/stores/\\d+/reviews(\\?.*)?$"),
				RegexRequestMatcher.regexMatcher(HttpMethod.GET, "^/stores/\\d+/bread-records(\\?.*)?$"),
				// 빵집 랭킹과 가게의 빵 평가는 회원과 무관한 전역 콘텐츠다 (/bread-rankings/me/** 는 boards 가 아니라 안 열린다)
				RegexRequestMatcher.regexMatcher(HttpMethod.GET, "^/stores/\\d+/bread-ratings(\\?.*)?$"),
				RegexRequestMatcher.regexMatcher(HttpMethod.GET, "^/bread-rankings/boards/\\d+(\\?.*)?$"),
				RegexRequestMatcher.regexMatcher(HttpMethod.GET, "^/menus/\\d+(\\?.*)?$"),
				RegexRequestMatcher.regexMatcher(HttpMethod.GET, "^/menus/\\d+/reviews(\\?.*)?$"),
				RegexRequestMatcher.regexMatcher(HttpMethod.GET, "^/menus/\\d+/reviews/summary(\\?.*)?$"),
				RegexRequestMatcher.regexMatcher(HttpMethod.GET, "^/users/\\d+/reviews(\\?.*)?$"),
				RegexRequestMatcher.regexMatcher(HttpMethod.GET, "^/courses/\\d+(\\?.*)?$"),
				// 초대 미리보기 — 랜딩은 사용자 토큰이 없다. /courses/** 로 열면 비공개 코스 API 가 통째로 열리니
				// 토큰 경로만 좁게 연다. 수락(POST)은 절대 열지 않는다.
				RegexRequestMatcher.regexMatcher(HttpMethod.GET, "^/courses/invites/[A-Za-z0-9_-]+(\\?.*)?$"),
				// 링크 공개 코스 입구 — 랜딩 /c/{shareToken} 도 사용자 토큰이 없다. 토큰 경로만 좁게 연다.
				RegexRequestMatcher.regexMatcher(HttpMethod.GET, "^/courses/by-share/[A-Za-z0-9_-]+(\\?.*)?$"))
			.permitAll()
			.requestMatchers("/admin/**", "/api/admin/**").permitAll()
			.requestMatchers(HttpMethod.POST, "/api/admin/members/*/ban").permitAll() // 회원 강퇴 API 임시 허용
			.anyRequest().authenticated());

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
			.successHandler(oAuth2SuccessHandler)
			// 로그인 실패 시 에러코드를 딥링크로 전달
			.failureHandler((request, response, exception) -> {
				log.error("OAuth2 로그인 실패 - 원인 확인용 임시 로그", exception);
				String errorCode = "LOGIN_FAILED";
				if (exception instanceof OAuth2AuthenticationException oae
					&& "MEMBER_BANNED".equals(oae.getError().getErrorCode())) {
					errorCode = "MEMBER_BANNED";
				}
				response.sendRedirect(frontRedirectUri + "?error=" + errorCode);
			}));

		http.exceptionHandling(exception -> exception.authenticationEntryPoint(apiAuthenticationEntryPoint));

		// JwtAuthenticationFilter를 UsernamePasswordAuthenticationFilter 앞에 등록
		http.addFilterBefore(
			new JwtAuthenticationFilter(jwtProvider),
			UsernamePasswordAuthenticationFilter.class);

		return http.build();
	}
}
