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
 * 어드민 API({@code /api/admin/**} · {@code /admin/**}) 전체의 공유 키를 확인한다.
 *
 * <p>{@code SecurityConfig}에서 어드민 경로는 permitAll이다 — 이 필터가 없으면 주소만 아는 누구나
 * 회원을 강퇴하고, 알림을 보내고, 가게와 메뉴를 고칠 수 있다. 처음에는 사용자에게 직접 닿는 편지만 잠갔고
 * 경로를 하나씩 더해 왔는데, 새 어드민 API 가 목록에서 빠진 채 열려 나가는 일이 반복돼(세계관) 전체를 잠근다.
 *
 * <p>키가 설정돼 있지 않으면 로컬에서만 통과시킨다. 편지·개인화는 기존 배포를 세우지 않으려고 로컬이 아니어도
 * 통과시키던 것을 그대로 둔다. <b>운영에는 반드시 {@code ADMIN_API_KEY}를 넣어야 한다.</b>
 */
public class AdminApiKeyFilter extends OncePerRequestFilter {

	public static final String HEADER = "X-Admin-Key";

	private static final Logger log = LoggerFactory.getLogger(AdminApiKeyFilter.class);
	/**
	 * 열쇠가 필요한 경로 — 어드민 API 전체. 새 어드민 컨트롤러는 이 두 뿌리 아래에 두면 따로 등록하지 않아도 잠긴다.
	 * AdminApiKeyConfig 의 URL 패턴과 짝이다.
	 */
	static final List<String> PROTECTED_PREFIXES = List.of("/api/admin/", "/admin/");

	/** 열쇠가 비었을 때 환경과 무관하게 막는 경로. 로컬에서도 열쇠 없이는 열리지 않는다. */
	static final List<String> KEY_REQUIRED_PREFIXES = List.of(
		"/api/admin/monthly-reports", "/api/admin/tutorial-templates", "/api/admin/rewards", "/api/admin/world");

	/**
	 * 열쇠가 비었을 때 로컬이 아니어도 <b>통과시키는</b> 경로. 전체를 잠그기 전부터 이렇게 돌던 것이라 그대로 둔다 —
	 * 열쇠 없이 띄운 기존 배포를 세우지 않으려는 원래 뜻이다. 그 밖의 경로는 열쇠가 비면 로컬에서만 통과한다.
	 */
	static final List<String> PASS_WITHOUT_KEY_PREFIXES = List.of("/api/admin/letters", "/admin/personalization");

	private final String apiKey;
	private final boolean allowWithoutKey;

	public AdminApiKeyFilter(String apiKey, boolean allowWithoutKey) {
		this.allowWithoutKey = allowWithoutKey;
		this.apiKey = apiKey == null ? "" : apiKey.trim();
		if (this.apiKey.isEmpty()) {
			log.warn("ADMIN_API_KEY가 비어 있다 — 어드민 API 는 {}. 열쇠가 꼭 필요한 경로({})는 503으로 막는다",
				allowWithoutKey ? "로컬이라 통과시킨다" : "503 으로 막는다(" + PASS_WITHOUT_KEY_PREFIXES + " 제외)",
				KEY_REQUIRED_PREFIXES);
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
		if (apiKey.isEmpty()) {
			if (!isKeyRequired(request.getRequestURI())
				&& (allowWithoutKey || passesWithoutKey(request.getRequestURI()))) {
				chain.doFilter(request, response);
				return;
			}
			// 열쇠 설정이 빠진 서버에서 어드민 API 를 열어 두지 않는다.
			log.error("열쇠 없이 보호 경로 요청 [uri={}, ip={}] — ADMIN_API_KEY 를 넣어야 한다",
				request.getRequestURI(), request.getRemoteAddr());
			write(response, HttpStatus.SERVICE_UNAVAILABLE, "ADMIN503",
				"어드민 열쇠가 설정되지 않아 이 경로를 막았습니다.");
			return;
		}
		if (matches(request.getHeader(HEADER))) {
			chain.doFilter(request, response);
			return;
		}

		log.warn("어드민 API 접근 거부 [uri={}, ip={}]", request.getRequestURI(), request.getRemoteAddr());
		write(response, HttpStatus.UNAUTHORIZED, "ADMIN401", "어드민 인증이 필요합니다.");
	}

	private static boolean isKeyRequired(String uri) {
		return KEY_REQUIRED_PREFIXES.stream().anyMatch(uri::startsWith);
	}

	private static boolean passesWithoutKey(String uri) {
		return PASS_WITHOUT_KEY_PREFIXES.stream().anyMatch(uri::startsWith);
	}

	private static void write(HttpServletResponse response, HttpStatus status, String code, String message)
			throws IOException {
		response.setStatus(status.value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.getWriter().write(
			"{\"success\":false,\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
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
