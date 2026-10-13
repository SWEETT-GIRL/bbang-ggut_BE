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
 * 편지·개인화·추천·월간 보고서 어드민 API의 공유 키를 확인한다.
 *
 * <p>{@code SecurityConfig}에서 {@code /api/admin/**}은 permitAll이다. 코스나 메뉴를 고치는
 * API라면 데이터가 지저분해지는 정도지만, <b>편지는 사용자에게 직접 닿는다</b> — 열어두면 아무나
 * 개발자를 사칭해 메시지를 보낼 수 있다. 사용자에게 영향을 주는 경로는 공유 키를 확인한다.
 *
 * <p>키가 설정돼 있지 않으면 통과시킨다. 로컬 개발과 기존 배포를 세우지 않으려는 것이고,
 * 대신 기동할 때마다 경고를 남긴다. 월간 보고서는 키 미설정 시 환경과 무관하게 거절한다. <b>운영에는 반드시 {@code ADMIN_API_KEY}를 넣어야 한다.</b>
 */
public class AdminApiKeyFilter extends OncePerRequestFilter {

	public static final String HEADER = "X-Admin-Key";

	private static final Logger log = LoggerFactory.getLogger(AdminApiKeyFilter.class);
	/**
	 * 열쇠가 필요한 경로. 편지는 사용자에게 직접 닿고, 개인화 배치(#663)는 취향 신호를 되돌리기 어렵게 바꾸거나 락을 쥔 채 무거운
	 * 트랜잭션을 건다 — 신호가 오염되면 추천이 바뀐다(PR #747 리뷰). 이유 문장 실험(#662)은 호출마다 외부 LLM 에 실제 비용이
	 * 나간다 — 열어 두면 아무나 우리 계정으로 돈을 쓸 수 있다. S3 정리는 버킷의 파일을 되돌릴 수 없게 지운다.
	 * 세계관은 행성·캐릭터를 고치고 회원에게 친구·깨우기 티켓을 쥐여 준다 — 열려 있으면 아무나 지급한다.
	 * 여기에 더하면 AdminApiKeyConfig 의 URL 패턴도 같이 더한다.
	 */
	static final List<String> PROTECTED_PREFIXES = List.of(
		"/api/admin/letters", "/admin/personalization", "/admin/recommendations", "/api/admin/monthly-reports",
		"/api/admin/tutorial-templates", "/admin/images/reconcile", "/api/admin/world");

	/**
	 * 열쇠가 비었을 때 <b>통과시키지 않고 막는</b> 경로. 호출마다 외부 LLM 에 실제 비용이 나가서, 열려 있으면
	 * 아무나 우리 계정으로 돈을 쓴다. 편지·개인화는 데이터가 지저분해지는 대가라 기존대로 경고 후 통과시킨다 —
	 * 열쇠 없이 띄운 로컬·기존 배포를 세우지 않으려는 원래 뜻을 여기까지 넓히지는 않는다. S3 정리도 같이 막는다 —
	 * 지운 파일은 돈으로도 되돌릴 수 없다.
	 */
	static final List<String> COST_INCURRING_PREFIXES = List.of("/admin/recommendations", "/admin/images/reconcile");

	private final String apiKey;
	private final boolean allowWithoutKey;

	public AdminApiKeyFilter(String apiKey, boolean allowWithoutKey) {
		this.allowWithoutKey = allowWithoutKey;
		this.apiKey = apiKey == null ? "" : apiKey.trim();
		if (this.apiKey.isEmpty()) {
			log.warn("ADMIN_API_KEY가 비어 있다 — 월간 보고서는 503으로 막는다. 기타 보호 경로: {}. "
				+ "비용이 나가는 경로({})는 {}", PROTECTED_PREFIXES, COST_INCURRING_PREFIXES,
				allowWithoutKey ? "로컬이라 통과시킨다" : "503 으로 막는다");
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
			if (!request.getRequestURI().startsWith("/api/admin/monthly-reports")
				&& !request.getRequestURI().startsWith("/api/admin/tutorial-templates")
				&& !request.getRequestURI().startsWith("/api/admin/world")
				&& (allowWithoutKey || !isCostIncurring(request.getRequestURI()))) {
				chain.doFilter(request, response);
				return;
			}
			// 비용 경로와 월간 보고서는 키 설정 누락 시 요청을 거절한다.
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

	private static boolean isCostIncurring(String uri) {
		return COST_INCURRING_PREFIXES.stream().anyMatch(uri::startsWith);
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
