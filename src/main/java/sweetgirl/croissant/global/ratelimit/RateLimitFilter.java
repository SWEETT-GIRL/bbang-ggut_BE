package sweetgirl.croissant.global.ratelimit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import sweetgirl.croissant.global.code.GeneralErrorCode;
import sweetgirl.croissant.global.response.ApiResponse;

/**
 * 로그인 전에도 부르는 API(게스트 발급·소셜 로그인·토큰 갱신·가게 검색·자동완성)를 IP 당 일정 횟수로 막는다.
 *
 * <p>새 인프라 없이 메모리(Caffeine)에서 센다 — WAS 가 한 대라 이걸로 충분하다. 창은 고정 창이다: IP 의 첫 요청부터
 * {@code window} 동안 센 뒤 통째로 비운다. 넘으면 429 + {@code COMMON429} + {@code Retry-After}.
 *
 * <p>IP 는 nginx 가 직접 채우는 {@code X-Real-IP}(= 접속한 주소)를 먼저 본다. {@code X-Forwarded-For} 의 첫 값은 클라이언트가
 * 지어 보낼 수 있어 제한을 우회하는 데 쓰이므로 보지 않는다.
 *
 * <p>통신사 NAT 뒤에서는 여러 사람이 한 IP 를 나눠 쓰므로 문턱은 넉넉하게 둔다(설정 {@code ops.rate-limit.*}).
 * Security 체인보다 앞이라 막힌 요청은 JWT 검증도 타지 않는다.
 */
public class RateLimitFilter extends OncePerRequestFilter {

	static final String REAL_IP_HEADER = "X-Real-IP";

	private final Map<RateLimitRule, Integer> limits;
	private final Duration window;
	private final Cache<String, AtomicInteger> counters;
	private final byte[] tooManyRequestsBody;

	public RateLimitFilter(RateLimitProperties properties, ObjectMapper objectMapper) {
		this.limits = properties.limits();
		this.window = properties.getWindow();
		this.counters = Caffeine.newBuilder()
			.expireAfterWrite(window)
			.maximumSize(properties.getMaxTrackedKeys())
			.build();
		this.tooManyRequestsBody = serialize(objectMapper);
	}

	private static byte[] serialize(ObjectMapper objectMapper) {
		try {
			return objectMapper.writeValueAsString(ApiResponse.fail(GeneralErrorCode._TOO_MANY_REQUESTS))
				.getBytes(StandardCharsets.UTF_8);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("429 응답 바디를 만들 수 없습니다.", e);
		}
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
			FilterChain filterChain) throws ServletException, IOException {
		String path = request.getRequestURI().substring(request.getContextPath().length());
		boolean blocked = RateLimitRule.match(request.getMethod(), path)
			.map(rule -> exceeded(rule, clientIp(request)))
			.orElse(false);
		if (!blocked) {
			filterChain.doFilter(request, response);
			return;
		}
		writeTooManyRequests(response);
	}

	private boolean exceeded(RateLimitRule rule, String ip) {
		int limit = limits.getOrDefault(rule, 0);
		if (limit <= 0) {
			return false;
		}
		AtomicInteger counter = counters.get(rule.name() + ":" + ip, key -> new AtomicInteger());
		return counter.incrementAndGet() > limit;
	}

	static String clientIp(HttpServletRequest request) {
		String realIp = request.getHeader(REAL_IP_HEADER);
		if (StringUtils.hasText(realIp)) {
			return realIp.strip();
		}
		return request.getRemoteAddr();
	}

	private void writeTooManyRequests(HttpServletResponse response) throws IOException {
		response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
		response.setHeader("Retry-After", String.valueOf(Math.max(1, window.toSeconds())));
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.setContentLength(tooManyRequestsBody.length);
		response.getOutputStream().write(tooManyRequestsBody);
	}
}
