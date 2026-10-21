package sweetgirl.croissant.config.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;

class ApiAuthenticationEntryPointTest {

	private final ApiAuthenticationEntryPoint entryPoint = new ApiAuthenticationEntryPoint();

	@Test
	void commence_returns401JsonWithoutRedirectLocation() throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();

		entryPoint.commence(
			new MockHttpServletRequest("GET", "/users/me/reviews"),
			response,
			new BadCredentialsException("required"));

		assertThat(response.getStatus()).isEqualTo(401);
		assertThat(response.getHeader("Location")).isNull();
		assertThat(response.getContentType()).isEqualTo("application/json;charset=UTF-8");
		assertThat(response.getContentAsString())
			.isEqualTo("{\"success\":false,\"code\":\"COMMON401\",\"message\":\"인증이 필요합니다.\",\"data\":null}");
	}
}
