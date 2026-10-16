package sweetgirl.croissant.global.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.fasterxml.jackson.databind.ObjectMapper;

class RateLimitFilterTest {

	private RateLimitFilter filterAllowing(int guestToken) {
		RateLimitProperties properties = new RateLimitProperties();
		properties.setWindow(Duration.ofMinutes(1));
		properties.setGuestToken(guestToken);
		properties.setStoreSearch(0);
		return new RateLimitFilter(properties, new ObjectMapper());
	}

	private MockHttpServletResponse call(RateLimitFilter filter, String method, String uri, String realIp)
			throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
		if (realIp != null) {
			request.addHeader(RateLimitFilter.REAL_IP_HEADER, realIp);
		}
		MockHttpServletResponse response = new MockHttpServletResponse();
		filter.doFilter(request, response, new MockFilterChain());
		return response;
	}

	@Test
	@DisplayName("문턱을 넘은 IP 는 429 · COMMON429 · Retry-After 를 받는다")
	void blocksAfterLimit() throws Exception {
		RateLimitFilter filter = filterAllowing(2);

		assertThat(call(filter, "POST", "/auth/guest", "1.1.1.1").getStatus()).isEqualTo(200);
		assertThat(call(filter, "POST", "/auth/guest", "1.1.1.1").getStatus()).isEqualTo(200);
		MockHttpServletResponse blocked = call(filter, "POST", "/auth/guest", "1.1.1.1");

		assertThat(blocked.getStatus()).isEqualTo(429);
		assertThat(blocked.getHeader("Retry-After")).isEqualTo("60");
		assertThat(blocked.getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
			.contains("\"success\":false")
			.contains("\"code\":\"COMMON429\"");
	}

	@Test
	@DisplayName("IP 마다 따로 센다")
	void countsPerIp() throws Exception {
		RateLimitFilter filter = filterAllowing(1);

		call(filter, "POST", "/auth/guest", "1.1.1.1");

		assertThat(call(filter, "POST", "/auth/guest", "2.2.2.2").getStatus()).isEqualTo(200);
		assertThat(call(filter, "POST", "/auth/guest", "1.1.1.1").getStatus()).isEqualTo(429);
	}

	@Test
	@DisplayName("대상이 아닌 경로·메서드, 문턱이 0 인 묶음은 막지 않는다")
	void passesNonTargets() throws Exception {
		RateLimitFilter filter = filterAllowing(1);

		for (int i = 0; i < 5; i++) {
			assertThat(call(filter, "POST", "/auth/logout", "1.1.1.1").getStatus()).isEqualTo(200);
			assertThat(call(filter, "GET", "/auth/guest", "1.1.1.1").getStatus()).isEqualTo(200);
			assertThat(call(filter, "GET", "/stores/search", "1.1.1.1").getStatus()).isEqualTo(200);
		}
	}

	@Test
	@DisplayName("X-Forwarded-For 는 지어 보낼 수 있어 보지 않는다 — X-Real-IP, 없으면 접속 주소")
	void clientIp_prefersRealIpAndIgnoresForwardedFor() {
		MockHttpServletRequest withRealIp = new MockHttpServletRequest("GET", "/stores/search");
		withRealIp.addHeader(RateLimitFilter.REAL_IP_HEADER, " 3.3.3.3 ");
		withRealIp.addHeader("X-Forwarded-For", "9.9.9.9");
		MockHttpServletRequest withoutRealIp = new MockHttpServletRequest("GET", "/stores/search");
		withoutRealIp.setRemoteAddr("4.4.4.4");
		withoutRealIp.addHeader("X-Forwarded-For", "9.9.9.9");

		assertThat(RateLimitFilter.clientIp(withRealIp)).isEqualTo("3.3.3.3");
		assertThat(RateLimitFilter.clientIp(withoutRealIp)).isEqualTo("4.4.4.4");
	}

	@Test
	@DisplayName("묶음 판정: 로그인 전 API 다섯 곳만")
	void ruleMatching() {
		assertThat(RateLimitRule.match("POST", "/auth/guest")).contains(RateLimitRule.GUEST_TOKEN);
		assertThat(RateLimitRule.match("POST", "/auth/kakao")).contains(RateLimitRule.AUTH_LOGIN);
		assertThat(RateLimitRule.match("POST", "/auth/apple")).contains(RateLimitRule.AUTH_LOGIN);
		assertThat(RateLimitRule.match("POST", "/auth/refresh")).contains(RateLimitRule.AUTH_REFRESH);
		assertThat(RateLimitRule.match("GET", "/stores/search")).contains(RateLimitRule.STORE_SEARCH);
		assertThat(RateLimitRule.match("GET", "/stores/autocomplete")).contains(RateLimitRule.STORE_AUTOCOMPLETE);
		assertThat(RateLimitRule.match("GET", "/stores/1")).isEmpty();
		assertThat(RateLimitRule.match("POST", "/auth/logout")).isEmpty();
	}
}
