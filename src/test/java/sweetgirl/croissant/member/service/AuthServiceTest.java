package sweetgirl.croissant.member.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.alarm.entity.FcmToken;
import sweetgirl.croissant.alarm.repository.FcmTokenRepository;
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

	@InjectMocks
	private AuthService authService;

	private static final String REFRESH_TOKEN = "valid.refresh.token";

	// ── refresh ──────────────────────────────────────────────────────────

	@Test
	void refresh_returnsNewAccessToken_whenRequestIsValid() {
		RefreshToken savedToken = mock(RefreshToken.class);

		given(jwtProvider.validateToken(REFRESH_TOKEN)).willReturn(true);
		given(refreshTokenRepository.findByToken(REFRESH_TOKEN)).willReturn(Optional.of(savedToken));
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
		given(refreshTokenRepository.findByToken(REFRESH_TOKEN)).willReturn(Optional.empty());

		assertThatThrownBy(() -> authService.refresh(REFRESH_TOKEN))
			.isInstanceOf(CustomException.class);
	}

	@Test
	void refresh_throwsException_whenTokenIsExpiredInDb() {
		RefreshToken expiredToken = mock(RefreshToken.class);

		given(jwtProvider.validateToken(REFRESH_TOKEN)).willReturn(true);
		given(refreshTokenRepository.findByToken(REFRESH_TOKEN)).willReturn(Optional.of(expiredToken));
		given(expiredToken.getExpiredAt()).willReturn(LocalDateTime.now().minusDays(1));

		assertThatThrownBy(() -> authService.refresh(REFRESH_TOKEN))
			.isInstanceOf(CustomException.class);
	}

	// ── logout ───────────────────────────────────────────────────────────

	@Test
	void logout_revokesToken_whenTokenExists() {
		RefreshToken savedToken = mock(RefreshToken.class);

		given(refreshTokenRepository.findByToken(REFRESH_TOKEN)).willReturn(Optional.of(savedToken));

		authService.logout(REFRESH_TOKEN, null);

		then(savedToken).should().revoke();
	}

	@Test
	void logout_doesNotThrow_whenTokenNotFound() {
		given(refreshTokenRepository.findByToken(REFRESH_TOKEN)).willReturn(Optional.empty());

		assertThatCode(() -> authService.logout(REFRESH_TOKEN, null)).doesNotThrowAnyException();
		then(refreshTokenRepository).should(never()).delete(any());
	}

	@Test
	void logout_deactivatesFcmToken_whenFcmTokenProvided() {
		RefreshToken savedToken = mock(RefreshToken.class);
		FcmToken fcmToken = mock(FcmToken.class);

		given(refreshTokenRepository.findByToken(REFRESH_TOKEN)).willReturn(Optional.of(savedToken));
		given(fcmTokenRepository.findByToken("fcm-token-value")).willReturn(Optional.of(fcmToken));

		authService.logout(REFRESH_TOKEN, "fcm-token-value");

		then(savedToken).should().revoke();
		then(fcmToken).should().deactivate();
	}

	@Test
	void logout_skipsFcmDeactivation_whenFcmTokenIsBlank() {
		RefreshToken savedToken = mock(RefreshToken.class);

		given(refreshTokenRepository.findByToken(REFRESH_TOKEN)).willReturn(Optional.of(savedToken));

		authService.logout(REFRESH_TOKEN, "   ");

		then(savedToken).should().revoke();
		then(fcmTokenRepository).shouldHaveNoInteractions();
	}
}
