package sweetgirl.croissant.course.draft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;

@DisplayName("MemoDraftLoadShedFilter")
class MemoDraftLoadShedFilterTest {

	private static final String DRAFT_URI = "/courses/12/stores/1090/memo-draft";

	private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

	private MemoDraftLoadShedFilter filterAllowing(int maxConcurrentWrites) {
		return new MemoDraftLoadShedFilter(maxConcurrentWrites, new ObjectMapper(), registry);
	}

	private static MockHttpServletResponse call(MemoDraftLoadShedFilter filter, String method, String uri,
			FilterChain chain) throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		filter.doFilter(new MockHttpServletRequest(method, uri), response, chain);
		return response;
	}

	/** 처리 중인 요청 안에서 같은 필터를 한 번 더 부른다 — 자리가 찬 상태의 두 번째 요청 */
	private static FilterChain whileBusy(MemoDraftLoadShedFilter filter, String method, String uri,
			MockHttpServletResponse[] second) {
		return (request, response) -> {
			try {
				second[0] = call(filter, method, uri, new MockFilterChain());
			} catch (Exception exception) {
				throw new ServletException(exception);
			}
		};
	}

	@Test
	@DisplayName("자리가 차 있으면 기다리지 않고 429 · COURSE429_1 · Retry-After, 체인은 타지 않는다")
	void shedsWhenFull() throws Exception {
		MemoDraftLoadShedFilter filter = filterAllowing(1);
		MockHttpServletResponse[] second = new MockHttpServletResponse[1];

		MockHttpServletResponse first = call(filter, "PUT", DRAFT_URI, whileBusy(filter, "PUT", DRAFT_URI, second));

		assertThat(first.getStatus()).isEqualTo(200);
		assertThat(second[0].getStatus()).isEqualTo(429);
		assertThat(second[0].getHeader("Retry-After")).isEqualTo("1");
		assertThat(second[0].getContentAsString(StandardCharsets.UTF_8))
			.contains("\"success\":false")
			.contains("\"code\":\"COURSE429_1\"");
		assertThat(registry.counter("draft_write_shed_total").count()).isEqualTo(1.0);
	}

	@Test
	@DisplayName("요청이 끝나면 자리를 돌려준다 — 예외로 끝나도")
	void releasesAfterCompletion() throws Exception {
		MemoDraftLoadShedFilter filter = filterAllowing(1);
		AtomicInteger passed = new AtomicInteger();
		FilterChain failing = (request, response) -> {
			throw new ServletException("boom");
		};

		assertThatThrownBy(() -> call(filter, "PUT", DRAFT_URI, failing)).isInstanceOf(ServletException.class);
		call(filter, "PUT", DRAFT_URI, (request, response) -> passed.incrementAndGet());
		call(filter, "PUT", DRAFT_URI, (request, response) -> passed.incrementAndGet());

		assertThat(passed.get()).isEqualTo(2);
		assertThat(registry.counter("draft_write_shed_total").count()).isZero();
		assertThat(registry.get("draft_write_in_flight").gauge().value()).isZero();
	}

	@Test
	@DisplayName("초안 PUT 이 아닌 요청은 세지 않는다 — 자리가 차 있어도 지나간다")
	void ignoresOtherRequests() throws Exception {
		MemoDraftLoadShedFilter filter = filterAllowing(1);
		MockHttpServletResponse[] drafts = new MockHttpServletResponse[1];
		MockHttpServletResponse[] detail = new MockHttpServletResponse[1];
		MockHttpServletResponse[] memo = new MockHttpServletResponse[1];

		call(filter, "PUT", DRAFT_URI, (request, response) -> {
			try {
				drafts[0] = call(filter, "GET", "/courses/12/drafts", new MockFilterChain());
				detail[0] = call(filter, "GET", "/courses/12", new MockFilterChain());
				memo[0] = call(filter, "PATCH", "/courses/12/stores/1090/memo", new MockFilterChain());
			} catch (Exception exception) {
				throw new ServletException(exception);
			}
		});

		assertThat(drafts[0].getStatus()).isEqualTo(200);
		assertThat(detail[0].getStatus()).isEqualTo(200);
		assertThat(memo[0].getStatus()).isEqualTo(200);
	}
}
