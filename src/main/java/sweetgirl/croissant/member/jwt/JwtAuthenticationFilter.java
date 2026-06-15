package sweetgirl.croissant.member.jwt;

import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// OncePerRequestFilter: 한 번의 HTTP 요청에 딱 한 번만 실행되도록 보장하는 필터
@Slf4j
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

	private static final Pattern MEMBER_REVIEWS_PATH = Pattern.compile("^/users/\\d+/reviews$");

	private final JwtProvider jwtProvider;

	@Override
	protected void doFilterInternal(
			HttpServletRequest request,
			HttpServletResponse response,
			FilterChain filterChain) throws ServletException, IOException {

		boolean shouldLogMemberReviewsAuth = isMemberReviewsRequest(request);

		// 요청 헤더에서 JWT 추출
		String token = resolveToken(request);
		if (shouldLogMemberReviewsAuth && token == null) {
			log.info("Profile reviews request has no bearer token. path={}", request.getRequestURI());
		}

		if (token != null && !jwtProvider.validateToken(token)) {
			if (shouldLogMemberReviewsAuth) {
				log.warn("Profile reviews JWT authentication failed. path={}, reason=invalid_token",
					request.getRequestURI());
			}
			response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
			response.setContentType("application/json;charset=UTF-8");
			response.getWriter().write("""
					{"success":false,"code":"COMMON401","message":"인증이 필요합니다.","data":null}""");
			return;
		}

		// 토큰이 존재하고 유효한 경우에만 인증 처리
		if (token != null && jwtProvider.validateToken(token)) {

			// 토큰에서 memberId 파싱
			Long memberId = jwtProvider.getMemberId(token);
			if (shouldLogMemberReviewsAuth) {
				log.info("Profile reviews JWT authenticated. path={}, memberId={}",
					request.getRequestURI(), memberId);
			}

			// Spring Security 인증 객체 생성
			// principal: memberId (이후 컨트롤러에서 @AuthenticationPrincipal Long memberId 로 꺼낼 수 있음)
			// credentials: null (JWT 방식에서는 불필요)
			// authorities: ROLE_USER 권한 부여
			UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
				memberId,
				null,
				List.of(new SimpleGrantedAuthority("ROLE_USER")));

			// SecurityContext에 인증 정보 등록
			// 이 시점부터 해당 요청은 "인증된 요청"으로 처리됨
			SecurityContextHolder.getContext().setAuthentication(authentication);
		}

		// 다음 필터로 요청 전달 (인증 실패해도 필터 체인은 계속 진행)
		// 인증 없이 보호된 경로에 접근하면 이후 Security가 403 응답 처리
		filterChain.doFilter(request, response);
	}

	// Authorization 헤더에서 Bearer 토큰 추출
	// 헤더 형식: "Authorization: Bearer eyJhbGci..."
	private String resolveToken(HttpServletRequest request) {
		String bearerToken = request.getHeader("Authorization");

		// "Bearer "로 시작하는 경우에만 토큰 부분만 잘라서 반환
		if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
			return bearerToken.substring(7); // "Bearer " 이후 문자열이 실제 토큰
		}

		return null;
	}

	private boolean isMemberReviewsRequest(HttpServletRequest request) {
		return "GET".equals(request.getMethod())
			&& MEMBER_REVIEWS_PATH.matcher(request.getRequestURI()).matches();
	}
}
