package sweetgirl.dataops.config.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 크롤러·승인 스크립트가 부르는 dataops API 를 키로 막는다. 배치 EC2 에서 같은 호스트의 Python 이 부르지만,
 * 운영 DB 반영까지 이어지는 API 라 포트가 열렸을 때를 대비한다.
 *
 * <p>키가 비어 있으면(로컬 세션) 검사하지 않는다. health 는 기동 대기용이라, 에이전트 제어면은 자체 토큰이 있어 뺀다.
 */
public class DataopsApiKeyFilter extends OncePerRequestFilter {

	public static final String HEADER = "X-Dataops-Key";
	private static final String HEALTH_PATH = "/actuator/health";
	private static final String AGENT_RUNTIME_PATH = "/internal/agent-runtime";

	private final byte[] apiKey;

	public DataopsApiKeyFilter(String apiKey) {
		this.apiKey = apiKey == null ? new byte[0] : apiKey.getBytes(StandardCharsets.UTF_8);
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		String path = request.getRequestURI();
		return apiKey.length == 0 || path.startsWith(HEALTH_PATH) || path.startsWith(AGENT_RUNTIME_PATH);
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		String given = request.getHeader(HEADER);
		if (given == null || !MessageDigest.isEqual(apiKey, given.getBytes(StandardCharsets.UTF_8))) {
			response.setStatus(HttpStatus.UNAUTHORIZED.value());
			response.setContentType(MediaType.APPLICATION_JSON_VALUE);
			response.setCharacterEncoding(StandardCharsets.UTF_8.name());
			response.getWriter()
				.write("{\"success\":false,\"code\":\"UNAUTHORIZED\",\"message\":\"" + HEADER + " 가 없거나 틀리다\"}");
			return;
		}
		filterChain.doFilter(request, response);
	}
}
