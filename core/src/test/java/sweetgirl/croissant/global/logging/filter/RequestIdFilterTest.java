package sweetgirl.croissant.global.logging.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;

class RequestIdFilterTest {

	private final RequestIdFilter filter = new RequestIdFilter();

	@Test
	@DisplayName("들어온 요청 id 를 이어 쓰고 MDC 와 응답 헤더에 싣는다")
	void reusesIncomingRequestId() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/stores/1");
		request.addHeader(RequestIdFilter.HEADER, "app-req-123");
		MockHttpServletResponse response = new MockHttpServletResponse();
		AtomicReference<String> seenInChain = new AtomicReference<>();

		filter.doFilter(request, response, (req, res) -> seenInChain.set(MDC.get(RequestIdFilter.MDC_REQUEST_ID)));

		assertThat(seenInChain.get()).isEqualTo("app-req-123");
		assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo("app-req-123");
	}

	@Test
	@DisplayName("헤더가 없으면 새 id 를 만든다")
	void generatesIdWhenHeaderMissing() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/stores/1");
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, new MockFilterChain());

		assertThat(response.getHeader(RequestIdFilter.HEADER)).hasSize(32).matches("[0-9a-f]+");
	}

	@Test
	@DisplayName("형식이 이상한 헤더는 버리고 새로 만든다")
	void replacesMalformedHeader() {
		assertThat(RequestIdFilter.resolve("a b")).isNotEqualTo("a b").hasSize(32);
		assertThat(RequestIdFilter.resolve("x".repeat(RequestIdFilter.MAX_LENGTH + 1))).hasSize(32);
		assertThat(RequestIdFilter.resolve("줄\n바꿈")).hasSize(32);
		assertThat(RequestIdFilter.resolve("  ")).hasSize(32);
		assertThat(RequestIdFilter.resolve(" ok-1 ")).isEqualTo("ok-1");
	}

	@Test
	@DisplayName("체인이 예외로 끝나도 MDC 를 지운다")
	void clearsMdcEvenWhenChainThrows() {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/stores/1");
		MockHttpServletResponse response = new MockHttpServletResponse();
		FilterChain failingChain = (req, res) -> {
			MDC.put(RequestIdFilter.MDC_MEMBER_ID, "7");
			throw new ServletException("boom");
		};

		assertThatThrownBy(() -> filter.doFilter(request, response, failingChain)).isInstanceOf(ServletException.class);

		assertThat(MDC.get(RequestIdFilter.MDC_REQUEST_ID)).isNull();
		assertThat(MDC.get(RequestIdFilter.MDC_MEMBER_ID)).isNull();
	}
}
