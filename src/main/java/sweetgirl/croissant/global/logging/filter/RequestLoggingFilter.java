package sweetgirl.croissant.global.logging.filter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter; // 요청당 딱 한 번만 실행되는 필터 기반 클래스
import org.springframework.web.util.ContentCachingRequestWrapper; // request body를 여러 번 읽을 수 있게 캐싱해주는 래퍼

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.global.logging.LogEventType;
import sweetgirl.croissant.global.logging.SensitiveUrlMasker;
import sweetgirl.croissant.global.logging.entity.RequestLog;
import sweetgirl.croissant.global.logging.service.RequestLogService;

@Slf4j
@RequiredArgsConstructor
public class RequestLoggingFilter extends OncePerRequestFilter {

	// GlobalExceptionHandler에서 에러 정보를 request attribute로 넘길 때 사용하는 키
	public static final String ATTR_ERROR_CODE = "LOG_ERROR_CODE";
	public static final String ATTR_ERROR_MESSAGE = "LOG_ERROR_MESSAGE";

	// /stores/123 처럼 URL 경로에서 store_id를 추출하는 정규식
	private static final Pattern STORE_ID_PATTERN = Pattern.compile("/stores/(\\d+)");

	// POST /stores/{id}/subscribe — 빵알람 구독(클릭) 엔드포인트 판별용
	private static final Pattern STORE_SUBSCRIBE_PATTERN = Pattern.compile("^/stores/(\\d+)/subscribe$");

	// body 저장 최대 길이 (10KB 초과분은 잘라냄)
	private static final int MAX_BODY_LENGTH = 10_000;

	// 로그를 남기지 않을 URL 접두사 목록 (Swagger, 액추에이터 등 인프라성 요청 제외)
	private static final String[] SKIP_PREFIXES = {"/swagger-ui", "/v3/api-docs", "/actuator"};

	private final RequestLogService requestLogService;

	// SKIP_PREFIXES 에 해당하는 URL이면 이 필터를 건너뜀
	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		String uri = request.getRequestURI();
		for (String prefix : SKIP_PREFIXES) {
			if (uri.startsWith(prefix)) {
				return true; // 필터 실행 안 함
			}
		}
		return false; // 나머지는 필터 실행
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
			FilterChain filterChain) throws ServletException, IOException {

		// body를 InputStream으로 한 번 읽으면 다시 읽을 수 없으므로, 읽은 내용을 내부적으로 캐싱해두는 래퍼로 교체
		ContentCachingRequestWrapper wrappedRequest = new ContentCachingRequestWrapper(request, MAX_BODY_LENGTH);

		long startTime = System.currentTimeMillis(); // 응답 시간 측정 시작

		try {
			filterChain.doFilter(wrappedRequest, response); // 다음 필터 → 컨트롤러 → 응답 처리
		} finally {
			// 요청이 성공하든 예외가 나든 항상 로그 저장 (finally 보장)
			saveLog(wrappedRequest, response, startTime);
		}
	}

	private void saveLog(ContentCachingRequestWrapper request, HttpServletResponse response, long startTime) {
		// 초대·공유 토큰이 경로(/courses/invites/{token}, /courses/by-share/{token})와 쿼리(?t=)에 실린다 —
		// 로그 라인과 request_logs 둘 다에 남기지 않는다. body 마스킹(maskSensitiveFields)은 경로·쿼리를 못 본다.
		String uri = SensitiveUrlMasker.maskPath(request.getRequestURI());
		String method = request.getMethod();
		int durationMs = (int)(System.currentTimeMillis() - startTime);
		String queryString = SensitiveUrlMasker.maskQuery(request.getQueryString());
		String clientIp = resolveClientIp(request);
		String userAgent = request.getHeader("User-Agent");

		RequestLog requestLog = RequestLog.builder()
			.memberId(resolveMemberId()) // JWT 필터가 SecurityContext에 넣어준 memberId
			.httpMethod(method) // GET, POST 등
			.endpoint(uri) // /api/v1/stores/123
			.queryParams(queryString) // ?lat=37.5&lng=127.0
			.requestBody(resolveBody(request)) // POST/PUT/PATCH body (PII 마스킹 후)
			.responseStatus(response.getStatus()) // 200, 400, 500 등 HTTP 상태코드
			.responseTimeMs(durationMs) // 응답 소요 시간(ms)
			.ipAddress(clientIp) // 클라이언트 IP (프록시 헤더 우선)
			.userAgent(userAgent) // 기기/OS/앱 정보
			.storeId(extractStoreId(uri)) // URL에서 추출한 store_id (없으면 null)
			.eventType(resolveEventType(method, uri)) // STORE_VIEW / ALARM_CLICK / null
			.errorCode((String)request.getAttribute(ATTR_ERROR_CODE)) // 예외 발생 시 GlobalExceptionHandler가 채워줌
			.errorMessage((String)request.getAttribute(ATTR_ERROR_MESSAGE))
			.build();

		log.info("API_PERF method={} uri={} query=\"{}\" status={} durationMs={} clientIp={} userAgent=\"{}\"",
			method,
			uri,
			normalizeLogValue(queryString, 500),
			response.getStatus(),
			durationMs,
			clientIp,
			normalizeLogValue(userAgent, 300));

		requestLogService.save(requestLog); // @Async("logExecutor") — 별도 스레드에서 저장 (응답 지연 없음)
	}

	// SecurityContextHolder에서 memberId 꺼내기 — JwtAuthenticationFilter가 Long으로 저장함
	private Long resolveMemberId() {
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		if (auth != null && auth.getPrincipal() instanceof Long memberId) {
			return memberId; // 로그인 사용자
		}
		return null; // 익명 요청
	}

	// URL 경로에서 store_id 추출: /stores/42/menu → 42
	private Long extractStoreId(String uri) {
		Matcher matcher = STORE_ID_PATTERN.matcher(uri);
		return matcher.find() ? Long.parseLong(matcher.group(1)) : null;
	}

	// 요청의 목적을 분류 — 가게 인기도 집계에 사용
	private LogEventType resolveEventType(String method, String uri) {
		if ("POST".equals(method) && STORE_SUBSCRIBE_PATTERN.matcher(uri).matches()) {
			return LogEventType.ALARM_CLICK;
		}
		if ("GET".equals(method) && STORE_ID_PATTERN.matcher(uri).find()) {
			return LogEventType.STORE_VIEW;
		}
		return null;
	}

	// POST/PUT/PATCH body만 저장 (GET은 body 없음)
	private String resolveBody(ContentCachingRequestWrapper request) {
		String method = request.getMethod();
		if (!"POST".equals(method) && !"PUT".equals(method) && !"PATCH".equals(method)) {
			return null;
		}
		byte[] buf = request.getContentAsByteArray(); // 컨트롤러가 이미 읽은 body를 캐시에서 꺼냄
		if (buf.length == 0) {
			return null; // body 없는 요청
		}
		// MAX_BODY_LENGTH 초과분은 잘라냄
		String body = new String(buf, 0, Math.min(buf.length, MAX_BODY_LENGTH), StandardCharsets.UTF_8);
		return maskSensitiveFields(body);
	}

	// 민감 필드를 *** 로 치환하여 개인정보 노출 방지
	private String maskSensitiveFields(String body) {
		return body
			.replaceAll("(?i)(\"password\"\\s*:\\s*\")([^\"]*)(\")", "$1***$3")
			.replaceAll("(?i)(\"token\"\\s*:\\s*\")([^\"]*)(\")", "$1***$3")
			.replaceAll("(?i)(\"authorization\"\\s*:\\s*\")([^\"]*)(\")", "$1***$3");
	}

	// X-Forwarded-For 헤더 우선 확인 (로드밸런서/프록시 뒤에 있을 때 실제 클라이언트 IP 가져오기)
	private String resolveClientIp(HttpServletRequest request) {
		String ip = request.getHeader("X-Forwarded-For");
		if (StringUtils.hasText(ip) && !"unknown".equalsIgnoreCase(ip)) {
			return ip.split(",")[0].trim(); // 여러 IP가 콤마로 이어진 경우 첫 번째가 원본 클라이언트
		}
		return request.getRemoteAddr(); // 직접 연결일 때
	}

	private String normalizeLogValue(String value, int maxLength) {
		if (!StringUtils.hasText(value)) {
			return "";
		}
		String normalized = value.replaceAll("[\\r\\n\\t]+", " ");
		if (normalized.length() <= maxLength) {
			return normalized;
		}
		return normalized.substring(0, maxLength) + "...";
	}
}
