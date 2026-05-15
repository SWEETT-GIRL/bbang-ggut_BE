package sweetgirl.croissant.member.service;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.global.code.GeneralErrorCode;
import sweetgirl.croissant.global.exception.CustomException;
import sweetgirl.croissant.member.entity.RefreshToken;
import sweetgirl.croissant.member.jwt.JwtProvider;
import sweetgirl.croissant.member.repository.RefreshTokenRepository;

@Service
@RequiredArgsConstructor
public class AuthService {

	private final JwtProvider jwtProvider;
	private final RefreshTokenRepository refreshTokenRepository;

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

		// DB에서 토큰 조회 — 존재하지 않으면 이미 로그아웃된 토큰
		RefreshToken savedToken = refreshTokenRepository.findByToken(refreshToken)
			.orElseThrow(() -> new CustomException(GeneralErrorCode._UNAUTHORIZED));

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
	@Transactional
	public void logout(String refreshToken) {

		// DB에서 토큰 조회 — 없으면 이미 로그아웃된 상태이므로 무시
		refreshTokenRepository.findByToken(refreshToken)
			.ifPresent(RefreshToken::revoke); // revokedAt = now() 설정 (dirty checking으로 자동 반영)
	}
}
