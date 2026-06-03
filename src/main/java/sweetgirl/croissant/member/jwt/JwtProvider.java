package sweetgirl.croissant.member.jwt;

import java.util.Date;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

@Component
public class JwtProvider {

	// application-local.properties의 jwt.secret 값을 주입받음
	// Base64로 인코딩된 문자열 → HMAC-SHA 서명 키로 변환해서 사용
	private final SecretKey secretKey;

	// Access Token 만료 시간 (ms 단위, 기본값 1시간 = 3600000)
	private final long accessTokenExpiry;

	// Refresh Token 만료 시간 (ms 단위, 기본값 14일 = 1209600000)
	private final long refreshTokenExpiry;

	public JwtProvider(
			@Value("${jwt.secret}") String secret,
			@Value("${jwt.access-token-expiry}") long accessTokenExpiry,
			@Value("${jwt.refresh-token-expiry}") long refreshTokenExpiry) {
		// Base64 인코딩된 secret 문자열을 디코딩하여 HMAC-SHA 서명 키 생성
		this.secretKey = Keys.hmacShaKeyFor(java.util.Base64.getDecoder().decode(secret));
		this.accessTokenExpiry = accessTokenExpiry;
		this.refreshTokenExpiry = refreshTokenExpiry;
	}

	// Access Token 생성
	public String generateAccessToken(Long memberId) {
		return buildToken(memberId, accessTokenExpiry);
	}

	// Refresh Token 생성
	public String generateRefreshToken(Long memberId) {
		return buildToken(memberId, refreshTokenExpiry);
	}

	// 토큰 생성메서드
	private String buildToken(Long memberId, long expiry) {
		Date now = new Date();
		Date expiryDate = new Date(now.getTime() + expiry);

		return Jwts.builder()
			.subject(String.valueOf(memberId)) // 토큰 주체: memberId
			.id(UUID.randomUUID().toString())
			.issuedAt(now) // 발급 시각
			.expiration(expiryDate) // 만료 시각
			.signWith(secretKey) // 서명 키로 HMAC-SHA 서명
			.compact();
	}

	// 토큰 유효성 검증
	public boolean validateToken(String token) {
		try {
			Jwts.parser()
				.verifyWith(secretKey) // 서명 검증
				.build()
				.parseSignedClaims(token);
			return true;
		} catch (JwtException | IllegalArgumentException e) {
			// 만료 시 에러 처리
			return false;
		}
	}

	// 토큰에서 memberId 파싱
	// JwtAuthenticationFilter에서 SecurityContext에 유저 정보를 등록할 때 사용
	public Long getMemberId(String token) {
		Claims claims = Jwts.parser()
			.verifyWith(secretKey)
			.build()
			.parseSignedClaims(token)
			.getPayload();

		// subject에 저장해둔 memberId를 Long으로 변환해서 반환
		return Long.parseLong(claims.getSubject());
	}
}
