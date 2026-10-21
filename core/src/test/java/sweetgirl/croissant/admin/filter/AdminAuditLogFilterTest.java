package sweetgirl.croissant.admin.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.FilterChain;
import sweetgirl.croissant.admin.entity.AdminAuditLog;
import sweetgirl.croissant.admin.service.AdminAuditLogCommandService;
import sweetgirl.croissant.global.logging.filter.RequestIdFilter;

class AdminAuditLogFilterTest {

	private final AdminAuditLogCommandService commandService = mock(AdminAuditLogCommandService.class);
	private final AdminAuditLogFilter filter = new AdminAuditLogFilter(commandService, new ObjectMapper(), "");

	@AfterEach
	void tearDown() {
		MDC.clear();
	}

	private AdminAuditLog recorded() {
		ArgumentCaptor<AdminAuditLog> captor = ArgumentCaptor.forClass(AdminAuditLog.class);
		verify(commandService).record(captor.capture());
		return captor.getValue();
	}

	private static MockHttpServletRequest jsonRequest(String method, String uri, String body) {
		MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
		request.setContentType("application/json");
		request.setContent(body.getBytes(StandardCharsets.UTF_8));
		return request;
	}

	/** 컨트롤러처럼 바디를 읽어야 캐시 래퍼에 남는다. */
	private static FilterChain readingChain(int status) {
		return (req, res) -> {
			req.getInputStream().readAllBytes();
			((MockHttpServletResponse)res).setStatus(status);
		};
	}

	@Test
	@DisplayName("쓰기 요청을 대상·상태·요청 id·가린 바디와 함께 남기고, 열쇠는 해시 앞자리만 남긴다")
	void recordsWriteRequest() throws Exception {
		MDC.put(RequestIdFilter.MDC_REQUEST_ID, "req-9");
		MockHttpServletRequest request = jsonRequest("POST", "/api/admin/members/12/ban",
			"{\"reason\":\"도배\",\"email\":\"a@b.c\","
				+ "\"nested\":{\"accessToken\":\"t\",\"apiKey\":\"k\"},\"list\":[{\"phone\":\"010\"}]}");
		request.addHeader("X-Admin-Key", "super-secret");
		request.addHeader("X-Real-IP", "1.2.3.4");
		request.addHeader("User-Agent", "admin-web");

		filter.doFilter(request, new MockHttpServletResponse(), readingChain(200));

		AdminAuditLog log = recorded();
		assertThat(log.getMethod()).isEqualTo("POST");
		assertThat(log.getPath()).isEqualTo("/api/admin/members/12/ban");
		assertThat(log.getTargetType()).isEqualTo("MEMBER");
		assertThat(log.getTargetId()).isEqualTo("12");
		assertThat(log.getStatusCode()).isEqualTo(200);
		assertThat(log.getRequestId()).isEqualTo("req-9");
		assertThat(log.getIp()).isEqualTo("1.2.3.4");
		assertThat(log.getUserAgent()).isEqualTo("admin-web");
		assertThat(log.getAdminLabel()).startsWith("key:").hasSize(12).doesNotContain("super-secret");
		assertThat(log.getRequestSummary())
			.contains("\"reason\":\"도배\"")
			.doesNotContain("a@b.c", "\"t\"", "\"k\"", "010")
			.contains("\"email\":\"***\"", "\"accessToken\":\"***\"", "\"apiKey\":\"***\"", "\"phone\":\"***\"");
	}

	@Test
	@DisplayName("설정한 열쇠 이름이 있으면 그 이름을 남긴다")
	void usesConfiguredLabel() {
		AdminAuditLogFilter labeled = new AdminAuditLogFilter(commandService, new ObjectMapper(), "dana");

		assertThat(labeled.label("any-key")).isEqualTo("dana");
		assertThat(filter.label(null)).isEqualTo("no-key");
	}

	@Test
	@DisplayName("어드민 조회(GET)는 남기지 않지만 회원 개인 정보 조회는 남긴다. 어드민이 아닌 경로는 대상이 아니다")
	void filtersByMethodAndPath() throws Exception {
		filter.doFilter(new MockHttpServletRequest("GET", "/api/admin/stores"), new MockHttpServletResponse(),
			readingChain(200));
		filter.doFilter(jsonRequest("POST", "/stores/1/subscribe", "{}"), new MockHttpServletResponse(),
			readingChain(200));
		verify(commandService, never()).record(any());

		filter.doFilter(new MockHttpServletRequest("GET", "/api/admin/members"), new MockHttpServletResponse(),
			readingChain(200));
		assertThat(recorded().getTargetType()).isEqualTo("MEMBER");
	}

	@Test
	@DisplayName("저장이 실패해도 어드민 요청은 그대로 끝난다")
	void swallowsStorageFailure() {
		doThrow(new IllegalStateException("db down")).when(commandService).record(any());
		MockHttpServletResponse response = new MockHttpServletResponse();

		assertThatCode(() -> filter.doFilter(jsonRequest("DELETE", "/admin/banners/3", "{}"), response,
			readingChain(204))).doesNotThrowAnyException();
		assertThat(response.getStatus()).isEqualTo(204);
	}

	@Test
	@DisplayName("JSON 이 아닌 바디는 크기만 남긴다")
	void summarizesNonJsonBodyBySize() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/admin/images/upload");
		request.setContentType("multipart/form-data; boundary=x");
		request.setContent(new byte[] {1, 2, 3});

		filter.doFilter(request, new MockHttpServletResponse(), readingChain(201));

		assertThat(recorded().getRequestSummary()).isEqualTo("(JSON 아님 · 3 bytes)");
	}

	@Test
	@DisplayName("긴 바디는 2KB 로 자른다")
	void truncatesSummary() throws Exception {
		filter.doFilter(jsonRequest("PUT", "/api/admin/rewards/settings",
			"{\"memo\":\"" + "가".repeat(5000) + "\"}"), new MockHttpServletResponse(), readingChain(200));

		assertThat(recorded().getRequestSummary()).hasSize(AdminAuditLogFilter.SUMMARY_MAX);
	}

	@Test
	@DisplayName("경로에서 대상 종류와 id 를 뽑는다")
	void derivesTargetFromPath() {
		assertThat(AdminAuditLogFilter.target("/api/admin/courses/5/stores")).containsExactly("COURSE", "5");
		assertThat(AdminAuditLogFilter.target("/api/admin/notifications/send")).containsExactly("PUSH", null);
		assertThat(AdminAuditLogFilter.target("/api/admin/monthly-reports/2026-10/publish"))
			.containsExactly("MONTHLY_REPORT", "2026-10");
		assertThat(AdminAuditLogFilter.target("/admin/app-version-policy")).containsExactly("APP_VERSION_POLICY", null);
		assertThat(AdminAuditLogFilter.target("/api/admin/")).containsExactly(null, null);
	}
}
