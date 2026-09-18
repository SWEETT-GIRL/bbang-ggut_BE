package sweetgirl.croissant.letter.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 열쇠가 필요한 어드민 API 앞을 지킨다 — 편지와 개인화 배치.
 *
 * <p>{@code SecurityConfig}에서 {@code /api/admin/**}은 permitAll이다. 코스나 메뉴를 고치는
 * API라면 데이터가 지저분해지는 정도지만, <b>편지는 사용자에게 직접 닿는다</b> — 열어두면 아무나
 * 개발자를 사칭해 메시지를 보낼 수 있다. 그래서 편지 경로에만 열쇠를 하나 건다.
 *
 * <p>키가 설정돼 있지 않으면 통과시킨다. 로컬 개발과 기존 배포를 세우지 않으려는 것이고,
 * 대신 기동할 때마다 경고를 남긴다. <b>운영에는 반드시 {@code ADMIN_API_KEY}를 넣어야 한다.</b>
 */
public class AdminApiKeyFilter extends OncePerRequestFilter {

	public static final String HEADER = "X-Admin-Key";

	private static final Logger log = LoggerFactory.getLogger(AdminApiKeyFilter.class);
	/**
	 * 열쇠가 필요한 경로. 편지는 사용자에게 직접 닿고, 개인화 배치(#663)는 취향 신호를 되돌리기 어렵게 바꾸거나 락을 쥔 채 무거운
	 * 트랜잭션을 건다 — 신호가 오염되면 추천이 바뀐다(PR #747 리뷰). 여기에 더하면 AdminApiKeyConfig 의 URL 패턴도 같이 더한다.
	 */
	static final List<String> PROTECTED_PREFIXES = List.of("/api/admin/letters", "/admin/personalization");

	private final String apiKey;

	public AdminApiKeyFilter(String apiKey) {
		this.apiKey = apiKey == null ? "" : apiKey.trim();
		if (this.apiKey.isEmpty()) {
			log.warn("ADMIN_API_KEY가 비어 있다 — 열쇠가 필요한 어드민 API({})가 무방비다. 운영에서는 반드시 설정할 것.",
				PROTECTED_PREFIXES);
		}
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		String uri = request.getRequestURI();
		return PROTECTED_PREFIXES.stream().noneMatch(uri::startsWith);
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if (apiKey.isEmpty() || matches(request.getHeader(HEADER))) {
			chain.doFilter(request, response);
			return;
		}

		log.warn("어드민 API 접근 거부 [uri={}, ip={}]", request.getRequestURI(), request.getRemoteAddr());
		response.setStatus(HttpStatus.UNAUTHORIZED.value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.getWriter().write(
			"{\"success\":false,\"code\":\"ADMIN401\",\"message\":\"어드민 인증이 필요합니다.\"}");
	}

	/** 길이로 정답을 흘리지 않게 상수 시간 비교를 쓴다. */
	private boolean matches(String provided) {
		if (provided == null) {
			return false;
		}
		return MessageDigest.isEqual(
			provided.trim().getBytes(StandardCharsets.UTF_8),
			apiKey.getBytes(StandardCharsets.UTF_8));
	}
}
