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

class JwtAuthenticationFilterTest {

	@Test
	void doFilterInternal_continuesAsAnonymousOnOptionalAuthGetRequestWhenBearerTokenIsInvalid() throws Exception {
		JwtProvider jwtProvider = mock(JwtProvider.class);
		given(jwtProvider.validateToken("invalid-token")).willReturn(false);
		JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtProvider);
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/users/1/reviews");
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain filterChain = mock(MockFilterChain.class);
		request.addHeader("Authorization", "Bearer invalid-token");

		filter.doFilterInternal(request, response, filterChain);

		assertThat(response.getStatus()).isEqualTo(200);
		assertThat(response.getHeader("Location")).isNull();
		assertThat(response.getContentAsString()).isEmpty();
		verify(filterChain).doFilter(request, response);
	}

	// 초대 미리보기·링크 공개 입구는 SecurityConfig 에서 비로그인 허용이다 — 만료된 Bearer 가 붙어 와도 익명으로 통과해야 한다
	@Test
	void doFilterInternal_continuesAsAnonymousOnInviteAndShareEntryPointsWhenBearerTokenIsInvalid() throws Exception {
		JwtProvider jwtProvider = mock(JwtProvider.class);
		given(jwtProvider.validateToken("invalid-token")).willReturn(false);
		JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtProvider);

		for (String path : new String[] {"/courses/invites/k9J_abc-XYZ", "/courses/by-share/Qm7xyz"}) {
			MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
			MockHttpServletResponse response = new MockHttpServletResponse();
			MockFilterChain filterChain = mock(MockFilterChain.class);
			request.addHeader("Authorization", "Bearer invalid-token");

			filter.doFilterInternal(request, response, filterChain);

			assertThat(response.getStatus()).isEqualTo(200);
			verify(filterChain).doFilter(request, response);
		}
	}

	// 수락(POST)은 절대 열지 않는다 — 같은 토큰 경로라도 인증 필수
	@Test
	void doFilterInternal_returns401OnInviteAcceptWhenBearerTokenIsInvalid() throws Exception {
		JwtProvider jwtProvider = mock(JwtProvider.class);
		given(jwtProvider.validateToken("invalid-token")).willReturn(false);
		JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtProvider);
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
		JwtProvider jwtProvider = mock(JwtProvider.class);
		given(jwtProvider.validateToken("invalid-token")).willReturn(false);
		JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtProvider);
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/users/me/reviews");
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain filterChain = mock(MockFilterChain.class);
		request.addHeader("Authorization", "Bearer invalid-token");

		filter.doFilterInternal(request, response, filterChain);

		assertThat(response.getStatus()).isEqualTo(401);
		assertThat(response.getHeader("Location")).isNull();
		assertThat(response.getContentType()).isEqualTo("application/json;charset=UTF-8");
		assertThat(response.getContentAsString())
			.isEqualTo("{\"success\":false,\"code\":\"COMMON401\",\"message\":\"인증이 필요합니다.\",\"data\":null}");
		verify(filterChain, never()).doFilter(request, response);
	}

	@Test
	void doFilterInternal_continuesWhenAuthorizationHeaderIsMissing() throws Exception {
		JwtProvider jwtProvider = mock(JwtProvider.class);
		JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtProvider);
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/users/1/reviews");
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain filterChain = mock(MockFilterChain.class);

		filter.doFilterInternal(request, response, filterChain);

		verify(filterChain).doFilter(request, response);
		verify(jwtProvider, never()).validateToken(org.mockito.ArgumentMatchers.anyString());
	}
}
