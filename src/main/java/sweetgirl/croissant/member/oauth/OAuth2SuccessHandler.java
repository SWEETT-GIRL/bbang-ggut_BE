package sweetgirl.croissant.member.oauth;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Arrays;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.global.encrypt.TokenHasher;
import sweetgirl.croissant.member.dto.dto.CustomOAuth2User;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.entity.RefreshToken;
import sweetgirl.croissant.member.jwt.JwtProvider;
import sweetgirl.croissant.member.repository.MemberRepository;
import sweetgirl.croissant.member.repository.RefreshTokenRepository;

// 카카오 로그인 성공 시 Spring Security가 자동으로 호출하는 핸들러
// JWT 발급 → RefreshToken DB 저장 → 앱 딥링크로 redirect
@Slf4j
@Component
@RequiredArgsConstructor
public class OAuth2SuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

	private final JwtProvider jwtProvider;
	private final MemberRepository memberRepository;
	private final RefreshTokenRepository refreshTokenRepository;
	// DB 저장 전 refreshToken을 SHA-256 해시로 변환하기 위해 주입
	private final TokenHasher tokenHasher;

	// application-local.properties의 oauth2.front-redirect-uri 값 주입 (bbanggut://oauth)
	@Value("${oauth2.front-redirect-uri}")
	private String frontRedirectUri;

	// application-local.properties의 jwt.refresh-token-expiry 값 주입 (ms 단위)
	@Value("${jwt.refresh-token-expiry}")
	private long refreshTokenExpiry;

	@Override
	@Transactional
	public void onAuthenticationSuccess(
			HttpServletRequest request,
			HttpServletResponse response,
			Authentication authentication) throws IOException {

		// authentication.getPrincipal()은 CustomOAuth2UserService.loadUser()의 반환값
		// CustomOAuth2User로 캐스팅해서 우리 DB의 memberId를 꺼냄
		CustomOAuth2User oAuth2User = (CustomOAuth2User)authentication.getPrincipal();
		Long memberId = oAuth2User.getMemberId();
		boolean isNew = oAuth2User.getIsNewMember();

		// CustomAuthorizationRequestResolver가 저장해둔 deviceId 쿠키에서 복원
		String deviceId = extractCookieValue(request, "deviceId");

		// JwtProvider로 Access Token, Refresh Token 발급
		String accessToken = jwtProvider.generateAccessToken(memberId);
		String refreshToken = jwtProvider.generateRefreshToken(memberId);

		// RefreshToken DB upsert: 같은 기기(deviceId)로 재로그인 시 기존 토큰 삭제 후 새 토큰 저장
		saveRefreshToken(memberId, deviceId, refreshToken);

		// deviceId 임시 쿠키 삭제 (더 이상 필요 없음, MaxAge=0으로 즉시 만료)
		clearCookie(response, "deviceId");

		// bbanggut://oauth?accessToken=xxx&refreshToken=yyy 형태로 앱에 딥링크 redirect
		String redirectUrl = "bbanggut://oauth"
			+ "?accessToken=" + accessToken
			+ "&refreshToken=" + refreshToken
			+ "&isNew=" + isNew;

		getRedirectStrategy().sendRedirect(request, response, redirectUrl);
	}

	// RefreshToken 저장: member + deviceId 조합이 이미 있으면 삭제 후 새로 저장
	private void saveRefreshToken(Long memberId, String deviceId, String refreshToken) {
		// JPA 프록시 참조 — DB 조회 없이 member FK만 세팅
		Member memberRef = memberRepository.getReferenceById(memberId);

		// 같은 기기로 재로그인 시 기존 토큰 삭제
		refreshTokenRepository.findByMemberAndDeviceId(memberRef, deviceId)
			.ifPresent(refreshTokenRepository::delete);

		// 새 RefreshToken 저장 — 원본 JWT 대신 SHA-256 해시값으로 저장
		refreshTokenRepository.save(
			RefreshToken.builder()
				.member(memberRef)
				.deviceId(deviceId)
				.token(tokenHasher.hash(refreshToken))
				.expiredAt(LocalDateTime.now().plusSeconds(refreshTokenExpiry / 1000)) // ms → 초 변환
				.build());
	}

	// 쿠키 배열에서 특정 이름의 쿠키 값 추출
	private String extractCookieValue(HttpServletRequest request, String cookieName) {
		if (request.getCookies() == null) {
			return null;
		}
		return Arrays.stream(request.getCookies())
			.filter(c -> cookieName.equals(c.getName()))
			.map(Cookie::getValue)
			.findFirst()
			.orElse(null);
	}

	// 쿠키 즉시 삭제 (MaxAge=0 설정)
	private void clearCookie(HttpServletResponse response, String cookieName) {
		Cookie cookie = new Cookie(cookieName, "");
		cookie.setMaxAge(0);
		cookie.setPath("/");
		response.addCookie(cookie);
	}
}
