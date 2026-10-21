package sweetgirl.dataops.config.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class DataopsApiKeyFilterTest {

	private static final String KEY = "test-dataops-key";

	@Test
	@DisplayName("키가 맞으면 통과시킨다")
	void matchingKeyPasses() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/sync-states/due");
		request.addHeader(DataopsApiKeyFilter.HEADER, KEY);

		MockFilterChain chain = filter(KEY, request);

		assertThat(chain.getRequest()).isNotNull();
	}

	@Test
	@DisplayName("키가 없거나 틀리면 401 로 막는다")
	void missingOrWrongKeyIsRejected() throws Exception {
		MockHttpServletRequest missing = new MockHttpServletRequest("POST", "/api/snapshots");
		MockHttpServletRequest wrong = new MockHttpServletRequest("PATCH", "/api/proposals/1/approve");
		wrong.addHeader(DataopsApiKeyFilter.HEADER, "wrong");

		MockHttpServletResponse missingResponse = new MockHttpServletResponse();
		MockFilterChain missingChain = new MockFilterChain();
		new DataopsApiKeyFilter(KEY).doFilter(missing, missingResponse, missingChain);
		MockHttpServletResponse wrongResponse = new MockHttpServletResponse();
		MockFilterChain wrongChain = new MockFilterChain();
		new DataopsApiKeyFilter(KEY).doFilter(wrong, wrongResponse, wrongChain);

		assertThat(missingResponse.getStatus()).isEqualTo(401);
		assertThat(missingChain.getRequest()).isNull();
		assertThat(wrongResponse.getStatus()).isEqualTo(401);
		assertThat(wrongChain.getRequest()).isNull();
		assertThat(wrongResponse.getContentAsString()).contains("\"success\":false");
	}

	@Test
	@DisplayName("health 와 에이전트 제어면은 키 없이 통과한다 — 기동 대기·자체 토큰")
	void healthAndAgentRuntimeAreExempt() throws Exception {
		MockFilterChain health = filter(KEY, new MockHttpServletRequest("GET", "/actuator/health"));
		MockFilterChain runtime = filter(KEY, new MockHttpServletRequest("GET", "/internal/agent-runtime"));

		assertThat(health.getRequest()).isNotNull();
		assertThat(runtime.getRequest()).isNotNull();
	}

	@Test
	@DisplayName("키를 설정하지 않으면(로컬 세션) 검사하지 않는다")
	void blankKeyDisablesCheck() throws Exception {
		MockFilterChain chain = filter("", new MockHttpServletRequest("POST", "/api/snapshots"));

		assertThat(chain.getRequest()).isNotNull();
	}

	private MockFilterChain filter(String key, MockHttpServletRequest request) throws Exception {
		MockFilterChain chain = new MockFilterChain();
		new DataopsApiKeyFilter(key).doFilter(request, new MockHttpServletResponse(), chain);
		return chain;
	}
}
