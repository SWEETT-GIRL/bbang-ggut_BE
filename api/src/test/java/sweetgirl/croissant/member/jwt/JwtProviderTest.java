package sweetgirl.croissant.member.jwt;

import static org.assertj.core.api.Assertions.*;

import java.util.Base64;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JwtProviderTest {

	// 테스트용 시크릿 (42바이트 = 336비트, HMAC-SHA256 최소 요건 256비트 충족)
	private static final String TEST_SECRET = Base64.getEncoder()
		.encodeToString("test-secret-key-for-jwt-provider-unit-test".getBytes());

	private JwtProvider jwtProvider;

	@BeforeEach
	void setUp() {
		jwtProvider = new JwtProvider(TEST_SECRET, 3_600_000L, 1_209_600_000L);
	}

	@Test
	void generateAccessToken_returnsNonBlankToken() {
		String token = jwtProvider.generateAccessToken(1L);
		assertThat(token).isNotBlank();
	}

	@Test
	void generateRefreshToken_returnsNonBlankToken() {
		String token = jwtProvider.generateRefreshToken(1L);
		assertThat(token).isNotBlank();
	}

	@Test
	void validateToken_returnsTrue_whenTokenIsValid() {
		String token = jwtProvider.generateAccessToken(1L);
		assertThat(jwtProvider.validateToken(token)).isTrue();
	}

	@Test
	void validateToken_returnsFalse_whenTokenIsExpired() {
		// expiry를 음수로 설정 → 발급 즉시 만료
		JwtProvider expiredProvider = new JwtProvider(TEST_SECRET, -1L, -1L);
		String token = expiredProvider.generateAccessToken(1L);

		assertThat(jwtProvider.validateToken(token)).isFalse();
	}

	@Test
	void validateToken_returnsFalse_whenTokenIsMalformed() {
		assertThat(jwtProvider.validateToken("invalid.token.string")).isFalse();
	}

	@Test
	void validateToken_returnsFalse_whenTokenIsEmpty() {
		assertThat(jwtProvider.validateToken("")).isFalse();
	}

	@Test
	void getMemberId_returnsCorrectMemberId() {
		Long memberId = 42L;
		String token = jwtProvider.generateAccessToken(memberId);

		assertThat(jwtProvider.getMemberId(token)).isEqualTo(memberId);
	}

	@Test
	void accessTokenAndRefreshToken_areDifferent() {
		Long memberId = 1L;
		String access = jwtProvider.generateAccessToken(memberId);
		String refresh = jwtProvider.generateRefreshToken(memberId);

		assertThat(access).isNotEqualTo(refresh);
	}
}
