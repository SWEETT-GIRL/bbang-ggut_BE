package sweetgirl.croissant.member.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.alarm.repository.FcmTokenRepository;
import sweetgirl.croissant.global.code.GeneralErrorCode;
import sweetgirl.croissant.global.encrypt.TokenHasher;
import sweetgirl.croissant.global.exception.CustomException;
import sweetgirl.croissant.member.entity.RefreshToken;
import sweetgirl.croissant.member.jwt.JwtProvider;
import sweetgirl.croissant.member.repository.RefreshTokenRepository;

@Service
@RequiredArgsConstructor
public class AuthService {

	private final JwtProvider jwtProvider;
	private final RefreshTokenRepository refreshTokenRepository;
	private final FcmTokenRepository fcmTokenRepository;
	private final TokenHasher tokenHasher;

	// Access Token 재발급
	// 1. JWT 유효성 검증 (만료/위변조)
	// 2. DB에 존재하는지 확인 (탈취된 토큰 차단)
	// 3. 만료 시각 확인
	// 4. 새 Access Token 발급
	@Transactional(readOnly = true)
	public String refresh(String refreshToken) {

		//  리프레시 토큰 서명/만료 검증
		if (!jwtProvider.validateToken(refreshToken)) {
			throw new CustomException(GeneralErrorCode._UNAUTHORIZED);
		}

		// DB에는 SHA-256 해시가 저장되어 있으므로, 조회 전에 동일하게 해시 변환
		List<RefreshToken> tokens = refreshTokenRepository.findAllByToken(tokenHasher.hash(refreshToken));
		if (tokens.isEmpty()) {
			throw new CustomException(GeneralErrorCode._UNAUTHORIZED);
		}
		RefreshToken savedToken = tokens.get(0);

		// revokedAt이 설정된 경우 — 로그아웃 또는 탈퇴로 무효화된 토큰
		if (savedToken.getRevokedAt() != null) {
			throw new CustomException(GeneralErrorCode._UNAUTHORIZED);
		}

		// DB에 저장된 만료 시각 확인
		if (savedToken.getExpiredAt().isBefore(LocalDateTime.now())) {
			throw new CustomException(GeneralErrorCode._UNAUTHORIZED);
		}

		// 토큰에서 memberId 파싱 후 새 Access Token 발급
		Long memberId = jwtProvider.getMemberId(refreshToken);
		return jwtProvider.generateAccessToken(memberId);
	}

	// 로그아웃: RefreshToken의 revokedAt을 현재 시각으로 설정
	// 삭제 대신 revoke하여 이력을 보존하고, refresh() 호출 시 revokedAt 체크로 재발급 차단
	// fcmToken이 함께 전달되면 해당 토큰도 비활성화하여 이후 푸시 미발송
	@Transactional
	public void logout(String refreshToken, String fcmToken) {

		// DB에는 SHA-256 해시가 저장되어 있으므로, 조회 전에 동일하게 해시 변환
		refreshTokenRepository.findAllByToken(tokenHasher.hash(refreshToken))
			.forEach(RefreshToken::revoke); // revokedAt = now() 설정 (dirty checking으로 자동 반영)

		// fcmToken이 같이 왔으면 비활성화 (FCM 토큰 없는 사용자도 있을 수 있어 optional)
		if (StringUtils.hasText(fcmToken)) {
			fcmTokenRepository.findAllByToken(fcmToken)
				.forEach(token -> token.deactivate());
		}
	}
}
