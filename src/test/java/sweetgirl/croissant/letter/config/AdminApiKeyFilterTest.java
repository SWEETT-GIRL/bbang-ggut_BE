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

		new AdminApiKeyFilter("secret", true).doFilter(request("/api/admin/letters/send", "secret"), response, chain);

		assertThat(response.getStatus()).isEqualTo(200);
		assertThat(chain.getRequest()).isNotNull();
	}

	@Test
	@DisplayName("키가 틀리면 401로 막는다")
	void blocksWithWrongKey() throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		new AdminApiKeyFilter("secret", true).doFilter(request("/api/admin/letters/send", "guess"), response, chain);

		assertThat(response.getStatus()).isEqualTo(401);
		assertThat(response.getContentAsString()).contains("ADMIN401");
		assertThat(chain.getRequest()).isNull();
	}

	@Test
	@DisplayName("키 헤더가 아예 없어도 막는다")
	void blocksWithoutHeader() throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		new AdminApiKeyFilter("secret", true).doFilter(request("/api/admin/letters/threads", null), response, chain);

		assertThat(response.getStatus()).isEqualTo(401);
		assertThat(chain.getRequest()).isNull();
	}

	@Test
	@DisplayName("키가 설정돼 있지 않으면 통과시킨다 — 로컬 개발을 세우지 않으려는 것")
	void passesWhenKeyNotConfigured() throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		new AdminApiKeyFilter("", true).doFilter(request("/api/admin/letters/send", null), response, chain);

		assertThat(response.getStatus()).isEqualTo(200);
		assertThat(chain.getRequest()).isNotNull();
	}

	@Test
	@DisplayName("개인화 배치 경로도 열쇠가 필요하다 — 신호를 되돌리기 어렵게 바꾸는 API(#663)")
	void protectsPersonalizationAdminPaths() throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		new AdminApiKeyFilter("secret", true)
			.doFilter(request("/admin/personalization/interactions/ingest", null), response, chain);

		assertThat(response.getStatus()).isEqualTo(401);
		assertThat(chain.getRequest()).isNull();
	}

	@Test
	@DisplayName("편지가 아닌 어드민 경로는 이 필터가 건드리지 않는다")
	void ignoresOtherAdminPaths() throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		new AdminApiKeyFilter("secret", true).doFilter(request("/api/admin/members/1/ban", null), response, chain);

		assertThat(response.getStatus()).isEqualTo(200);
		assertThat(chain.getRequest()).isNotNull();
	}

	@Test
	@DisplayName("열쇠가 비면 비용 경로는 503 으로 막는다 — 열려 있으면 아무나 우리 계정으로 돈을 쓴다")
	void blocksCostIncurringPathWhenKeyMissing() throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		new AdminApiKeyFilter("", false)
			.doFilter(request("/admin/recommendations/reason-experiment", null), response, chain);

		assertThat(response.getStatus()).isEqualTo(503);
		assertThat(response.getContentAsString()).contains("ADMIN503");
		assertThat(chain.getRequest()).isNull();
	}

	@Test
	@DisplayName("S3 정리는 열쇠가 없으면 막는다 — 지운 파일은 되돌릴 수 없다")
	void protectsS3ReconcilePath() throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		new AdminApiKeyFilter("secret", false)
			.doFilter(request("/admin/images/reconcile", null), response, chain);

		assertThat(response.getStatus()).isEqualTo(401);
		assertThat(chain.getRequest()).isNull();
	}

	@Test
	@DisplayName("서버에 열쇠가 비어 있으면 S3 정리는 503 으로 막는다")
	void blocksS3ReconcileWhenKeyMissing() throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		new AdminApiKeyFilter("", false)
			.doFilter(request("/admin/images/reconcile/latest", null), response, chain);

		assertThat(response.getStatus()).isEqualTo(503);
		assertThat(chain.getRequest()).isNull();
	}

	@Test
	@DisplayName("S3 정리 말고 다른 이미지 어드민 경로는 건드리지 않는다")
	void ignoresOtherImageAdminPaths() throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		new AdminApiKeyFilter("secret", false)
			.doFilter(request("/admin/images/presigned-url", null), response, chain);

		assertThat(chain.getRequest()).isNotNull();
	}

	@Test
	@DisplayName("열쇠가 비어도 로컬이면 비용 경로를 통과시킨다 — 개발 편의는 지킨다")
	void allowsCostIncurringPathOnLocal() throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		new AdminApiKeyFilter("", true)
			.doFilter(request("/admin/recommendations/reason-experiment", null), response, chain);

		assertThat(chain.getRequest()).isNotNull();
	}

	@Test
	@DisplayName("열쇠가 비었을 때 비용이 아닌 경로는 기존대로 통과시킨다")
	void stillAllowsOtherPathsWhenKeyMissing() throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		new AdminApiKeyFilter("", false).doFilter(request("/admin/personalization/profiles/refresh", null),
			response, chain);

		assertThat(chain.getRequest()).isNotNull();
	}

	@Test
	void monthlyReportsRequireConfiguredKeyEvenLocally() throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();
		new AdminApiKeyFilter("", true).doFilter(request("/api/admin/monthly-reports/2026-09/runs", null), response,
			chain);
		assertThat(response.getStatus()).isEqualTo(503);
		assertThat(chain.getRequest()).isNull();
	}

	@Test
	void monthlyReportPreviewRejectsMissingHeader() throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();
		new AdminApiKeyFilter("secret", false)
			.doFilter(request("/api/admin/monthly-reports/2026-09/runs/id/preview/1", null), response, chain);
		assertThat(response.getStatus()).isEqualTo(401);
		assertThat(chain.getRequest()).isNull();
	}

	@Test
	void monthlyReportAssetsAcceptCorrectKey() throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();
		new AdminApiKeyFilter("secret", false).doFilter(request("/api/admin/monthly-reports/assets", "secret"),
			response, chain);
		assertThat(chain.getRequest()).isNotNull();
	}

	@Test
	@DisplayName("세계관 어드민은 열쇠가 없으면 막는다 — 회원에게 친구·티켓을 쥐여 주는 경로")
	void protectsWorldAdminPaths() throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		new AdminApiKeyFilter("secret", false)
			.doFilter(request("/api/admin/world/members/1/grants", null), response, chain);

		assertThat(response.getStatus()).isEqualTo(401);
		assertThat(chain.getRequest()).isNull();
	}

	@Test
	@DisplayName("세계관 어드민은 올바른 열쇠면 통과시킨다")
	void passesWorldAdminWithCorrectKey() throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		new AdminApiKeyFilter("secret", false)
			.doFilter(request("/api/admin/world/planets", "secret"), response, chain);

		assertThat(chain.getRequest()).isNotNull();
	}

	@Test
	@DisplayName("서버에 열쇠가 비어 있으면 세계관 어드민은 로컬에서도 503 으로 막는다")
	void blocksWorldAdminWhenKeyMissingEvenLocally() throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		new AdminApiKeyFilter("", true).doFilter(request("/api/admin/world/planets", null), response, chain);

		assertThat(response.getStatus()).isEqualTo(503);
		assertThat(chain.getRequest()).isNull();
	}
}
