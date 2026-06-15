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
	void doFilterInternal_returns401JsonWhenBearerTokenIsInvalid() throws Exception {
		JwtProvider jwtProvider = mock(JwtProvider.class);
		given(jwtProvider.validateToken("invalid-token")).willReturn(false);
		JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtProvider);
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/users/1/reviews");
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
