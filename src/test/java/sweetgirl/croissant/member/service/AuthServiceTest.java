package sweetgirl.croissant.member.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.alarm.entity.FcmToken;
import sweetgirl.croissant.alarm.repository.FcmTokenRepository;
import sweetgirl.croissant.global.encrypt.TokenHasher;
import sweetgirl.croissant.global.exception.CustomException;
import sweetgirl.croissant.member.entity.RefreshToken;
import sweetgirl.croissant.member.jwt.JwtProvider;
import sweetgirl.croissant.member.repository.RefreshTokenRepository;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

	@Mock
	private JwtProvider jwtProvider;

	@Mock
	private RefreshTokenRepository refreshTokenRepository;

	@Mock
	private FcmTokenRepository fcmTokenRepository;

	@Mock
	private TokenHasher tokenHasher;

	@InjectMocks
	private AuthService authService;

	private static final String REFRESH_TOKEN = "valid.refresh.token";

	@BeforeEach
	void setUp() {
		given(tokenHasher.hash(any())).willAnswer(inv -> inv.getArgument(0));
	}

	// ── refresh ──────────────────────────────────────────────────────────

	@Test
	void refresh_returnsNewAccessToken_whenRequestIsValid() {
		RefreshToken savedToken = mock(RefreshToken.class);

		given(jwtProvider.validateToken(REFRESH_TOKEN)).willReturn(true);
		given(refreshTokenRepository.findAllByToken(REFRESH_TOKEN)).willReturn(List.of(savedToken));
		given(savedToken.getExpiredAt()).willReturn(LocalDateTime.now().plusDays(7));
		given(jwtProvider.getMemberId(REFRESH_TOKEN)).willReturn(1L);
		given(jwtProvider.generateAccessToken(1L)).willReturn("new.access.token");

		String result = authService.refresh(REFRESH_TOKEN);

		assertThat(result).isEqualTo("new.access.token");
	}

	@Test
	void refresh_throwsException_whenJwtSignatureIsInvalid() {
		given(jwtProvider.validateToken(REFRESH_TOKEN)).willReturn(false);

		assertThatThrownBy(() -> authService.refresh(REFRESH_TOKEN))
			.isInstanceOf(CustomException.class);
	}

	@Test
	void refresh_throwsException_whenTokenNotFoundInDb() {
		given(jwtProvider.validateToken(REFRESH_TOKEN)).willReturn(true);
		given(refreshTokenRepository.findAllByToken(REFRESH_TOKEN)).willReturn(List.of());

		assertThatThrownBy(() -> authService.refresh(REFRESH_TOKEN))
			.isInstanceOf(CustomException.class);
	}

	@Test
	void refresh_throwsException_whenTokenIsExpiredInDb() {
		RefreshToken expiredToken = mock(RefreshToken.class);

		given(jwtProvider.validateToken(REFRESH_TOKEN)).willReturn(true);
		given(refreshTokenRepository.findAllByToken(REFRESH_TOKEN)).willReturn(List.of(expiredToken));
		given(expiredToken.getExpiredAt()).willReturn(LocalDateTime.now().minusDays(1));

		assertThatThrownBy(() -> authService.refresh(REFRESH_TOKEN))
			.isInstanceOf(CustomException.class);
	}

	// ── logout ───────────────────────────────────────────────────────────

	@Test
	void logout_revokesToken_whenTokenExists() {
		RefreshToken savedToken = mock(RefreshToken.class);

		given(refreshTokenRepository.findAllByToken(REFRESH_TOKEN)).willReturn(List.of(savedToken));

		authService.logout(REFRESH_TOKEN, null);

		then(savedToken).should().revoke();
	}

	@Test
	void logout_revokesAllTokens_whenRefreshTokenDuplicated() {
		RefreshToken firstToken = mock(RefreshToken.class);
		RefreshToken secondToken = mock(RefreshToken.class);

		given(refreshTokenRepository.findAllByToken(REFRESH_TOKEN)).willReturn(List.of(firstToken, secondToken));

		authService.logout(REFRESH_TOKEN, null);

		then(firstToken).should().revoke();
		then(secondToken).should().revoke();
	}

	@Test
	void logout_doesNotThrow_whenTokenNotFound() {
		given(refreshTokenRepository.findAllByToken(REFRESH_TOKEN)).willReturn(List.of());

		assertThatCode(() -> authService.logout(REFRESH_TOKEN, null)).doesNotThrowAnyException();
		then(refreshTokenRepository).should(never()).delete(any());
	}

	@Test
	void logout_deactivatesFcmToken_whenFcmTokenProvided() {
		RefreshToken savedToken = mock(RefreshToken.class);
		FcmToken fcmToken = mock(FcmToken.class);

		given(refreshTokenRepository.findAllByToken(REFRESH_TOKEN)).willReturn(List.of(savedToken));
		given(fcmTokenRepository.findAllByToken("fcm-token-value")).willReturn(List.of(fcmToken));

		authService.logout(REFRESH_TOKEN, "fcm-token-value");

		then(savedToken).should().revoke();
		then(fcmToken).should().deactivate();
	}

	@Test
	void logout_deactivatesAllFcmTokens_whenFcmTokenDuplicated() {
		RefreshToken savedToken = mock(RefreshToken.class);
		FcmToken firstFcmToken = mock(FcmToken.class);
		FcmToken secondFcmToken = mock(FcmToken.class);

		given(refreshTokenRepository.findAllByToken(REFRESH_TOKEN)).willReturn(List.of(savedToken));
		given(fcmTokenRepository.findAllByToken("fcm-token-value")).willReturn(List.of(firstFcmToken, secondFcmToken));

		authService.logout(REFRESH_TOKEN, "fcm-token-value");

		then(savedToken).should().revoke();
		then(firstFcmToken).should().deactivate();
		then(secondFcmToken).should().deactivate();
	}

	@Test
	void logout_skipsFcmDeactivation_whenFcmTokenIsBlank() {
		RefreshToken savedToken = mock(RefreshToken.class);

		given(refreshTokenRepository.findAllByToken(REFRESH_TOKEN)).willReturn(List.of(savedToken));

		authService.logout(REFRESH_TOKEN, "   ");

		then(savedToken).should().revoke();
		then(fcmTokenRepository).shouldHaveNoInteractions();
	}
}
