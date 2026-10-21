package sweetgirl.croissant.coupon.filter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import sweetgirl.croissant.coupon.code.CouponErrorCode;
import sweetgirl.croissant.coupon.issuer.CouponSoldOutFlags;
import sweetgirl.croissant.global.response.ApiResponse;

/**
 * 소진된 쿠폰의 발급 요청을 <b>Security 필터 체인보다 앞에서</b> 거절한다.
 *
 * <p>왜 필요한가 — 측정에서 거절 응답의 p50 이 13,279ms 였는데 처리 자체는 최소 7ms 였다.
 * <b>99.95% 가 톰캣 스레드를 얻으려고 줄 서 있던 시간이다.</b> 톰캣 스레드 200개가 30초간 포화인 동안
 * 커넥션 풀은 pending 0, idle 6~7 로 한가했다. 즉 {@link CouponSoldOutFlags} 가 커넥션은 지켰지만
 * 스레드는 못 지켰다. 그 결과가 두 가지다 — 탈락자가 13초를 기다리고(모바일 타임아웃 10초를 넘긴다),
 * 쿠폰과 무관한 검색 요청이 스레드를 못 얻어 p90 17.1초가 됐다.
 *
 * <p>판정은 이미 공짜다({@code AtomicBoolean.get()} 0.24ns). 비싼 것은 답을 아는 일이 아니라
 * <b>답을 전달하는 일</b>이었다 — JWT 검증, Security 체인, MVC 디스패치, Jackson 직렬화.
 * 그래서 판정을 요청 경로의 맨 앞으로 당겨 스레드를 곧바로 놔준다.
 *
 * <p><b>품절 거절에는 인증이 필요 없다.</b> 소진됐으면 누가 요청했든 거절이므로 {@code memberId} 를
 * 알 필요가 없다. 그래서 JWT 를 건너뛰어도 판단이 달라지지 않는다.
 *
 * <p><b>한 방향으로만 틀리게 만든다</b> — {@link CouponSoldOutFlags} 와 같은 원칙이다.
 * URL 이 안 맞거나 {@code couponId} 를 못 읽거나 플래그가 꺼져 있으면 <b>전부 통과시킨다.</b>
 * 덜 막으면 뒤쪽(앱·DB)이 정확히 판정하므로 느릴 뿐 안전하지만, 과하게 막으면 재고가 남았는데
 * 거절하게 되고 그건 되돌릴 수 없다.
 *
 * <p>거절 로그는 남기지 않는다. 이벤트 한 번에 거절이 만 건 단위라 로그가 그 자체로 비용이 되고
 * (동기 {@code ConsoleAppender} 기준 실측 6.5µs/건) 진짜 에러를 묻는다. 대신 건수는
 * {@code coupon_issue_rejected_total} 카운터로 본다 — 여기서 응답을 끝내면 Micrometer 의
 * {@code http_server_requests_seconds} 에 잡히지 않으므로, 이 카운터가 없으면 거절 건수를
 * 관측할 수단이 사라진다.
 */
public class CouponSoldOutFilter extends OncePerRequestFilter {

	/** {@code POST /coupons/{couponId}/issue} 만 대상이다. 조회({@code /coupons/{id}})를 잡으면 안 된다. */
	private static final Pattern ISSUE_PATH = Pattern.compile("^/coupons/(\\d{1,19})/issue$");

	private final CouponSoldOutFlags soldOutFlags;
	private final Counter rejectedCounter;

	/**
	 * 응답 바디를 미리 직렬화해 둔다. 요청마다 Jackson 을 돌리면 이 필터가 줄이려는 비용이 그대로 남는다.
	 * 기동 시 한 번 {@link ApiResponse} 로 만들기 때문에 컨트롤러가 내려주는 포맷과 어긋날 수 없다.
	 */
	private final byte[] soldOutBody;

	public CouponSoldOutFilter(
			CouponSoldOutFlags soldOutFlags,
			MeterRegistry meterRegistry,
			ObjectMapper objectMapper) {
		this.soldOutFlags = soldOutFlags;
		this.rejectedCounter = Counter.builder("coupon.issue.rejected")
			.description("품절 플래그로 앞단에서 거절한 쿠폰 발급 요청 수")
			.tag("reason", "sold_out_filter")
			.register(meterRegistry);
		this.soldOutBody = serializeSoldOutBody(objectMapper);
	}

	private static byte[] serializeSoldOutBody(ObjectMapper objectMapper) {
		try {
			return objectMapper
				.writeValueAsString(ApiResponse.fail(CouponErrorCode.COUPON_SOLD_OUT))
				.getBytes(StandardCharsets.UTF_8);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("품절 응답 바디를 만들 수 없습니다.", e);
		}
	}

	@Override
	protected void doFilterInternal(
			HttpServletRequest request,
			HttpServletResponse response,
			FilterChain filterChain) throws ServletException, IOException {
		Long couponId = issueTargetCouponId(request);
		if (couponId == null || !soldOutFlags.isSoldOut(couponId)) {
			filterChain.doFilter(request, response);
			return;
		}
		rejectedCounter.increment();
		writeSoldOut(response);
	}

	/** 발급 요청이 아니거나 {@code couponId} 를 읽을 수 없으면 {@code null} — 통과시킨다는 뜻이다. */
	private Long issueTargetCouponId(HttpServletRequest request) {
		if (!"POST".equals(request.getMethod())) {
			return null;
		}
		String path = request.getRequestURI().substring(request.getContextPath().length());
		Matcher matcher = ISSUE_PATH.matcher(path);
		if (!matcher.matches()) {
			return null;
		}
		try {
			return Long.valueOf(matcher.group(1));
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/**
	 * Security 보다 앞이라 뒤쪽 필터들이 손대지 않는다. 한글 메시지가 있으므로 charset 을 명시한다 —
	 * 빠뜨리면 클라이언트가 깨진 문자열을 받는다.
	 */
	private void writeSoldOut(HttpServletResponse response) throws IOException {
		response.setStatus(HttpStatus.CONFLICT.value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.setContentLength(soldOutBody.length);
		response.getOutputStream().write(soldOutBody);
	}
}
