package sweetgirl.croissant.member.service;

import java.time.LocalDateTime;
import java.time.LocalTime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.member.dto.dto.AppleTokenClaims;
import sweetgirl.croissant.member.dto.response.AppleLoginResponse;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.entity.NotificationSetting;
import sweetgirl.croissant.member.entity.OAuth;
import sweetgirl.croissant.member.entity.RefreshToken;
import sweetgirl.croissant.member.enums.NotificationRadius;
import sweetgirl.croissant.member.enums.OAuthProvider;
import sweetgirl.croissant.member.jwt.JwtProvider;
import sweetgirl.croissant.member.repository.MemberRepository;
import sweetgirl.croissant.member.repository.NotificationSettingRepository;
import sweetgirl.croissant.member.repository.OAuthRepository;
import sweetgirl.croissant.member.repository.RefreshTokenRepository;

@Service
@RequiredArgsConstructor
public class AppleAuthService {

	private final AppleTokenVerifier appleTokenVerifier;
	private final OAuthRepository oAuthRepository;
	private final MemberRepository memberRepository;
	private final RefreshTokenRepository refreshTokenRepository;
	private final NotificationSettingRepository notificationSettingRepository;
	private final JwtProvider jwtProvider;

	@Value("${jwt.refresh-token-expiry}")
	private long refreshTokenExpiry;

	@Transactional
	public AppleLoginResponse login(String identityToken, String deviceId) {
		AppleTokenClaims claims = appleTokenVerifier.verify(identityToken);

		boolean existsOAuth = oAuthRepository.existsByProviderAndProviderId(OAuthProvider.APPLE, claims.sub());

		OAuth oAuth = oAuthRepository
			.findByProviderAndProviderId(OAuthProvider.APPLE, claims.sub())
			.orElseGet(() -> registerNewMember(claims));

		Member member = oAuth.getMember();
		boolean isDeletedMember = member.isDeleted();
		if (isDeletedMember) {
			member.reactivate();
		}
		// 신규 가입이거나 탈퇴 후 재가입이면 isNew = true
		boolean isNew = !existsOAuth || isDeletedMember;

		notificationSettingRepository.findByMemberId(member.getId())
			.orElseGet(() -> createDefaultNotificationSetting(member));

		Long memberId = member.getId();
		String accessToken = jwtProvider.generateAccessToken(memberId);
		String refreshToken = jwtProvider.generateRefreshToken(memberId);

		saveRefreshToken(memberId, deviceId, refreshToken);

		return new AppleLoginResponse(accessToken, refreshToken, isNew);
	}

	private OAuth registerNewMember(AppleTokenClaims claims) {
		Member member = memberRepository.save(
			Member.builder()
				.nickname("애플유저")
				.build());

		return oAuthRepository.save(
			OAuth.builder()
				.member(member)
				.provider(OAuthProvider.APPLE)
				.providerId(claims.sub())
				.email(claims.email())
				.build());
	}

	private NotificationSetting createDefaultNotificationSetting(Member member) {
		return notificationSettingRepository.save(
			NotificationSetting.builder()
				.member(member)
				.startTime(LocalTime.of(8, 0))
				.endTime(LocalTime.of(21, 0))
				.daysOfWeek("")
				.notificationRadius(NotificationRadius.R500)
				.isMarketingEnabled(false)
				.isNotificationEnabled(false)
				.build());
	}

	private void saveRefreshToken(Long memberId, String deviceId, String refreshToken) {
		Member memberRef = memberRepository.getReferenceById(memberId);

		refreshTokenRepository.findByMemberAndDeviceId(memberRef, deviceId).ifPresent(refreshTokenRepository::delete);
		refreshTokenRepository.save(
			RefreshToken.builder()
				.member(memberRef)
				.deviceId(deviceId)
				.token(refreshToken)
				.expiredAt(LocalDateTime.now().plusSeconds(refreshTokenExpiry / 1000))
				.build());
	}
}
