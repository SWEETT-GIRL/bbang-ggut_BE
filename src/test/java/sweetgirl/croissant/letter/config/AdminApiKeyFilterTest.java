package sweetgirl.croissant.letter.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AdminApiKeyFilterTest {

	private MockHttpServletRequest request(String uri, String key) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
		request.setRequestURI(uri);
		if (key != null) {
			request.addHeader(AdminApiKeyFilter.HEADER, key);
		}
		return request;
	}

	@Test
	@DisplayName("올바른 키면 통과시킨다")
	void passesWithCorrectKey() throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		new AdminApiKeyFilter("secret").doFilter(request("/api/admin/letters/send", "secret"), response, chain);

		assertThat(response.getStatus()).isEqualTo(200);
		assertThat(chain.getRequest()).isNotNull();
	}

	@Test
	@DisplayName("키가 틀리면 401로 막는다")
	void blocksWithWrongKey() throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		new AdminApiKeyFilter("secret").doFilter(request("/api/admin/letters/send", "guess"), response, chain);

		assertThat(response.getStatus()).isEqualTo(401);
		assertThat(response.getContentAsString()).contains("ADMIN401");
		assertThat(chain.getRequest()).isNull();
	}

	@Test
	@DisplayName("키 헤더가 아예 없어도 막는다")
	void blocksWithoutHeader() throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		new AdminApiKeyFilter("secret").doFilter(request("/api/admin/letters/threads", null), response, chain);

		assertThat(response.getStatus()).isEqualTo(401);
		assertThat(chain.getRequest()).isNull();
	}

	@Test
	@DisplayName("키가 설정돼 있지 않으면 통과시킨다 — 로컬 개발을 세우지 않으려는 것")
	void passesWhenKeyNotConfigured() throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		new AdminApiKeyFilter("").doFilter(request("/api/admin/letters/send", null), response, chain);

		assertThat(response.getStatus()).isEqualTo(200);
		assertThat(chain.getRequest()).isNotNull();
	}

	@Test
	@DisplayName("편지가 아닌 어드민 경로는 이 필터가 건드리지 않는다")
	void ignoresOtherAdminPaths() throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		new AdminApiKeyFilter("secret").doFilter(request("/api/admin/members/1/ban", null), response, chain);

		assertThat(response.getStatus()).isEqualTo(200);
		assertThat(chain.getRequest()).isNotNull();
	}
}
