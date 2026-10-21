package sweetgirl.croissant.global.logging.filter;

import java.io.IOException;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 요청마다 id 를 정해 MDC({@code requestId})와 응답 헤더({@code X-Request-Id})에 싣는다.
 *
 * <p>앱이나 nginx 가 {@code X-Request-Id} 를 보내면 그 값을 이어 쓰고, 없거나 형식이 이상하면 새로 만든다.
 * 로그 한 줄·슬랙 장애 알림·앱이 받은 응답 헤더가 같은 값을 가리키게 해, 한 요청을 끝까지 따라간다.
 *
 * <p>가장 바깥에서 돈다 — Security·쿠폰 품절 필터가 남기는 로그에도 id 가 붙어야 한다. MDC 는 톰캣 스레드에
 * 남으므로 끝나면 지운다. {@code memberId} 는 JWT 필터가 넣지만 지우는 일은 여기서 한다(예외로 빠져나가도 지워지게).
 */
public class RequestIdFilter extends OncePerRequestFilter {

	public static final String HEADER = "X-Request-Id";
	public static final String MDC_REQUEST_ID = "requestId";
	public static final String MDC_MEMBER_ID = "memberId";

	static final int MAX_LENGTH = 64;

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
			FilterChain filterChain) throws ServletException, IOException {
		String requestId = resolve(request.getHeader(HEADER));
		MDC.put(MDC_REQUEST_ID, requestId);
		response.setHeader(HEADER, requestId);
		try {
			filterChain.doFilter(request, response);
		} finally {
			MDC.remove(MDC_REQUEST_ID);
			MDC.remove(MDC_MEMBER_ID);
		}
	}

	/** 클라이언트 입력이라 로그·헤더를 오염시키지 않는 값(출력 가능한 ASCII, 64자 이하)만 이어 쓴다. */
	static String resolve(String raw) {
		if (raw == null) {
			return newId();
		}
		String value = raw.strip();
		if (value.isEmpty() || value.length() > MAX_LENGTH) {
			return newId();
		}
		for (int i = 0; i < value.length(); i++) {
			char ch = value.charAt(i);
			if (ch < 0x21 || ch > 0x7E) {
				return newId();
			}
		}
		return value;
	}

	private static String newId() {
		return UUID.randomUUID().toString().replace("-", "");
	}
}
