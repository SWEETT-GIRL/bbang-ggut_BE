package sweetgirl.croissant.member.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import sweetgirl.croissant.global.logging.filter.RequestIdFilter;

/**
 * 토큰을 낸 요청은 경로와 무관하게 유효해야 한다(RFC 6750). 틀리면 401 + WWW-Authenticate 로 갱신을 유도하고,
 * 토큰이 없는 요청만 익명으로 흘려 SecurityConfig 의 permitAll 이 처리하게 둔다.
 */
class JwtAuthenticationFilterTest {

	private static final String INVALID_TOKEN_CHALLENGE = "Bearer error=\"invalid_token\"";

	private static JwtAuthenticationFilter filterRejecting(String token) {
		JwtProvider jwtProvider = mock(JwtProvider.class);
		given(jwtProvider.validateToken(token)).willReturn(false);
		return new JwtAuthenticationFilter(jwtProvider);
	}

	// 비로그인 허용 GET(프로필 리뷰)이라도 틀린 토큰은 401 — 익명으로 강등하지 않는다
	@Test
	void doFilterInternal_returns401OnPublicGetWhenBearerTokenIsInvalid() throws Exception {
		JwtAuthenticationFilter filter = filterRejecting("invalid-token");
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/users/1/reviews");
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain filterChain = mock(MockFilterChain.class);
		request.addHeader("Authorization", "Bearer invalid-token");

		filter.doFilterInternal(request, response, filterChain);

		assertThat(response.getStatus()).isEqualTo(401);
		assertThat(response.getHeader("WWW-Authenticate")).isEqualTo(INVALID_TOKEN_CHALLENGE);
		assertThat(response.getHeader("Location")).isNull();
		verify(filterChain, never()).doFilter(request, response);
	}

	// 만료된 토큰을 든 멤버가 코스 상세를 폴링하면 익명 403 이 아니라 401 을 받아 갱신 후 재시도한다
	@Test
	void doFilterInternal_returns401OnCourseDetailWhenBearerTokenIsExpired() throws Exception {
		JwtAuthenticationFilter filter = filterRejecting("expired-token");
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/courses/1");
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain filterChain = mock(MockFilterChain.class);
		request.addHeader("Authorization", "Bearer expired-token");

		filter.doFilterInternal(request, response, filterChain);

		assertThat(response.getStatus()).isEqualTo(401);
		assertThat(response.getHeader("WWW-Authenticate")).isEqualTo(INVALID_TOKEN_CHALLENGE);
		verify(filterChain, never()).doFilter(request, response);
	}

	// 초대 미리보기·링크 공개 입구도 같다 — 토큰을 냈으면 유효해야 한다. 토큰 없이 오는 경우는 아래 테스트가 덮는다
	@Test
	void doFilterInternal_returns401OnInviteAndShareEntryPointsWhenBearerTokenIsInvalid() throws Exception {
		JwtAuthenticationFilter filter = filterRejecting("invalid-token");

		for (String path : new String[] {"/courses/invites/k9J_abc-XYZ", "/courses/by-share/Qm7xyz"}) {
			MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
			MockHttpServletResponse response = new MockHttpServletResponse();
			MockFilterChain filterChain = mock(MockFilterChain.class);
			request.addHeader("Authorization", "Bearer invalid-token");

			filter.doFilterInternal(request, response, filterChain);

			assertThat(response.getStatus()).as(path).isEqualTo(401);
			assertThat(response.getHeader("WWW-Authenticate")).as(path).isEqualTo(INVALID_TOKEN_CHALLENGE);
			verify(filterChain, never()).doFilter(request, response);
		}
	}

	// 수락(POST)은 절대 열지 않는다 — 같은 토큰 경로라도 인증 필수
	@Test
	void doFilterInternal_returns401OnInviteAcceptWhenBearerTokenIsInvalid() throws Exception {
		JwtAuthenticationFilter filter = filterRejecting("invalid-token");
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/courses/invites/k9J_abc/accept");
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain filterChain = mock(MockFilterChain.class);
		request.addHeader("Authorization", "Bearer invalid-token");

		filter.doFilterInternal(request, response, filterChain);

		assertThat(response.getStatus()).isEqualTo(401);
		verify(filterChain, never()).doFilter(request, response);
	}

	@Test
	void doFilterInternal_returns401JsonOnProtectedRequestWhenBearerTokenIsInvalid() throws Exception {
		JwtAuthenticationFilter filter = filterRejecting("invalid-token");
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/users/me/reviews");
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain filterChain = mock(MockFilterChain.class);
		request.addHeader("Authorization", "Bearer invalid-token");

		filter.doFilterInternal(request, response, filterChain);

		assertThat(response.getStatus()).isEqualTo(401);
		assertThat(response.getHeader("WWW-Authenticate")).isEqualTo(INVALID_TOKEN_CHALLENGE);
		assertThat(response.getHeader("Location")).isNull();
		assertThat(response.getContentType()).isEqualTo("application/json;charset=UTF-8");
		assertThat(response.getContentAsString())
			.isEqualTo("{\"success\":false,\"code\":\"COMMON401\",\"message\":\"인증이 필요합니다.\",\"data\":null}");
		verify(filterChain, never()).doFilter(request, response);
	}

	// 토큰이 없으면 검증 없이 흘려보낸다 — 비로그인 허용 여부는 SecurityConfig 의 permitAll 이 정한다
	@Test
	void doFilterInternal_continuesAsAnonymousWhenAuthorizationHeaderIsMissing() throws Exception {
		JwtProvider jwtProvider = mock(JwtProvider.class);
		JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtProvider);

		for (String path : new String[] {
			"/users/1/reviews", "/courses/1", "/courses/invites/k9J_abc-XYZ", "/courses/by-share/Qm7xyz"}) {
			MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
			MockHttpServletResponse response = new MockHttpServletResponse();
			MockFilterChain filterChain = mock(MockFilterChain.class);

			filter.doFilterInternal(request, response, filterChain);

			assertThat(response.getStatus()).as(path).isEqualTo(200);
			assertThat(response.getHeader("WWW-Authenticate")).as(path).isNull();
			verify(filterChain).doFilter(request, response);
		}
		verify(jwtProvider, never()).validateToken(org.mockito.ArgumentMatchers.anyString());
	}

	// 인증된 요청은 로그 줄에 memberId 가 붙는다 (지우는 일은 RequestIdFilter)
	@Test
	void doFilterInternal_putsMemberIdIntoMdcWhenAuthenticated() throws Exception {
		JwtProvider jwtProvider = mock(JwtProvider.class);
		given(jwtProvider.validateToken("valid-token")).willReturn(true);
		given(jwtProvider.getMemberId("valid-token")).willReturn(42L);
		JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtProvider);
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/users/me");
		request.addHeader("Authorization", "Bearer valid-token");
		String[] seen = new String[1];

		try {
			filter.doFilterInternal(request, new MockHttpServletResponse(),
				(req, res) -> seen[0] = org.slf4j.MDC.get(RequestIdFilter.MDC_MEMBER_ID));
		} finally {
			org.slf4j.MDC.clear();
			org.springframework.security.core.context.SecurityContextHolder.clearContext();
		}

		assertThat(seen[0]).isEqualTo("42");
	}
}
