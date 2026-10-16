package sweetgirl.croissant.global.filter;

import java.io.IOException;
import java.time.Clock;

import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 모든 응답에 서버 시각(epoch ms)을 {@code X-Server-Time} 으로 싣는다 — 앱이 기기 시계가 틀어졌는지 보고 맞출 수 있게.
 *
 * <p>헤더는 응답이 커밋되기 전에만 붙일 수 있어 요청이 들어온 시각을 쓴다(처리 시간만큼 이르다).
 * 초 단위 {@code Date} 헤더는 톰캣이 이미 붙인다.
 */
public class ServerTimeHeaderFilter extends OncePerRequestFilter {

	public static final String HEADER = "X-Server-Time";

	private final Clock clock;

	public ServerTimeHeaderFilter(Clock clock) {
		this.clock = clock;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
			FilterChain filterChain) throws ServletException, IOException {
		response.setHeader(HEADER, String.valueOf(clock.millis()));
		filterChain.doFilter(request, response);
	}
}
