package sweetgirl.croissant.coupon.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import sweetgirl.croissant.coupon.issuer.CouponSoldOutFlags;

/**
 * 이 필터는 <b>과하게 막으면 되돌릴 수 없다</b>(재고가 남았는데 거절). 그래서 "막는 케이스" 하나보다
 * <b>"통과시켜야 하는 케이스"</b>를 더 많이 검증한다.
 */
class CouponSoldOutFilterTest {

	private static final long COUPON_ID = 7L;

	private MeterRegistry meterRegistry;
	private MockFilterChain filterChain;
	private MockHttpServletResponse response;

	@BeforeEach
	void setUp() {
		meterRegistry = new SimpleMeterRegistry();
		filterChain = new MockFilterChain();
		response = new MockHttpServletResponse();
	}

	private CouponSoldOutFilter filterWith(boolean flagEnabled, boolean soldOut) {
		CouponSoldOutFlags flags = new CouponSoldOutFlags(flagEnabled);
		if (soldOut) {
			flags.markSoldOut(COUPON_ID);
		}
		return new CouponSoldOutFilter(flags, meterRegistry, new ObjectMapper());
	}

	private MockHttpServletRequest issueRequest(String uri) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
		request.setRequestURI(uri);
		return request;
	}

	private double rejectedCount() {
		return meterRegistry.get("coupon.issue.rejected").tag("reason", "sold_out_filter").counter().count();
	}

	@Nested
	@DisplayName("품절된 쿠폰이면")
	class WhenSoldOut {

		@Test
		@DisplayName("체인을 타지 않고 409 를 바로 응답한다")
		void rejectsWithoutEnteringChain() throws Exception {
			filterWith(true, true)
				.doFilter(issueRequest("/coupons/7/issue"), response, filterChain);

			assertThat(response.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
			// 체인에 넘기지 않았다는 증거 — 넘겼다면 MockFilterChain 이 요청을 기록한다.
			assertThat(filterChain.getRequest()).isNull();
		}

		@Test
		@DisplayName("컨트롤러와 같은 ApiResponse 포맷으로 응답한다")
		void writesApiResponseBody() throws Exception {
			filterWith(true, true)
				.doFilter(issueRequest("/coupons/7/issue"), response, filterChain);

			// charset 이 Content-Type 에 실려야 한다 — 빠지면 클라이언트가 한글 메시지를 깨뜨린다.
			assertThat(response.getContentType())
				.startsWith(MediaType.APPLICATION_JSON_VALUE)
				.containsIgnoringCase(StandardCharsets.UTF_8.name());
			assertThat(response.getContentAsString(StandardCharsets.UTF_8))
				.contains("\"success\":false")
				.contains("\"code\":\"COUPON_SOLD_OUT\"")
				.contains("쿠폰이 모두 소진되었습니다.");
		}

		@Test
		@DisplayName("거절 건수를 카운터에 남긴다 — 여기서 끝내면 http_server_requests 에 안 잡히므로")
		void incrementsCounter() throws Exception {
			CouponSoldOutFilter filter = filterWith(true, true);

			filter.doFilter(issueRequest("/coupons/7/issue"), response, filterChain);
			filter.doFilter(issueRequest("/coupons/7/issue"), new MockHttpServletResponse(), new MockFilterChain());

			assertThat(rejectedCount()).isEqualTo(2.0);
		}
	}

	@Nested
	@DisplayName("통과시켜야 하는 경우")
	class PassesThrough {

		@Test
		@DisplayName("재고가 남아 있으면 통과한다")
		void whenStockRemains() throws Exception {
			MockHttpServletRequest request = issueRequest("/coupons/7/issue");

			filterWith(true, false).doFilter(request, response, filterChain);

			assertThat(filterChain.getRequest()).isSameAs(request);
			assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
			assertThat(rejectedCount()).isZero();
		}

		@Test
		@DisplayName("플래그 설정이 꺼져 있으면 품절이어도 통과한다 — 비관락 구현과 호환")
		void whenFlagDisabled() throws Exception {
			MockHttpServletRequest request = issueRequest("/coupons/7/issue");

			filterWith(false, true).doFilter(request, response, filterChain);

			assertThat(filterChain.getRequest()).isSameAs(request);
			assertThat(rejectedCount()).isZero();
		}

		@Test
		@DisplayName("다른 쿠폰 id 면 통과한다 — 품절은 쿠폰별로 따로다")
		void whenDifferentCoupon() throws Exception {
			MockHttpServletRequest request = issueRequest("/coupons/8/issue");

			filterWith(true, true).doFilter(request, response, filterChain);

			assertThat(filterChain.getRequest()).isSameAs(request);
		}

		@Test
		@DisplayName("발급이 아닌 조회 경로는 통과한다")
		void whenNotIssuePath() throws Exception {
			MockHttpServletRequest request = issueRequest("/coupons/7");

			filterWith(true, true).doFilter(request, response, filterChain);

			assertThat(filterChain.getRequest()).isSameAs(request);
		}

		@Test
		@DisplayName("POST 가 아니면 통과한다")
		void whenNotPost() throws Exception {
			MockHttpServletRequest request = new MockHttpServletRequest("GET", "/coupons/7/issue");
			request.setRequestURI("/coupons/7/issue");

			filterWith(true, true).doFilter(request, response, filterChain);

			assertThat(filterChain.getRequest()).isSameAs(request);
		}

		@Test
		@DisplayName("couponId 가 숫자가 아니면 통과한다 — 막는 쪽으로 틀리지 않는다")
		void whenCouponIdNotNumeric() throws Exception {
			MockHttpServletRequest request = issueRequest("/coupons/abc/issue");

			filterWith(true, true).doFilter(request, response, filterChain);

			assertThat(filterChain.getRequest()).isSameAs(request);
		}

		@Test
		@DisplayName("couponId 가 Long 범위를 넘으면 통과한다")
		void whenCouponIdOverflows() throws Exception {
			MockHttpServletRequest request = issueRequest("/coupons/99999999999999999999/issue");

			filterWith(true, true).doFilter(request, response, filterChain);

			assertThat(filterChain.getRequest()).isSameAs(request);
		}
	}
}
