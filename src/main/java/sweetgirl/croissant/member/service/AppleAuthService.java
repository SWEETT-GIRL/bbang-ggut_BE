package sweetgirl.croissant.member.service;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.global.encrypt.TokenHasher;
import sweetgirl.croissant.member.code.MemberErrorCode;
import sweetgirl.croissant.member.dto.dto.AppleTokenClaims;
import sweetgirl.croissant.member.dto.response.AppleLoginResponse;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.entity.NotificationSetting;
import sweetgirl.croissant.member.entity.OAuth;
import sweetgirl.croissant.member.entity.RefreshToken;
import sweetgirl.croissant.member.enums.MemberRestrictionType;
import sweetgirl.croissant.member.enums.NotificationRadius;
import sweetgirl.croissant.member.enums.OAuthProvider;
import sweetgirl.croissant.member.exception.MemberException;
import sweetgirl.croissant.member.jwt.JwtProvider;
import sweetgirl.croissant.member.repository.MemberRepository;
import sweetgirl.croissant.member.repository.MemberRestrictionRepository;
import sweetgirl.croissant.member.repository.NotificationSettingRepository;
import sweetgirl.croissant.member.repository.OAuthRepository;
import sweetgirl.croissant.member.repository.RefreshTokenRepository;
import sweetgirl.croissant.member.util.NicknameGenerator;

@Service
@RequiredArgsConstructor
public class AppleAuthService {

	private final AppleTokenVerifier appleTokenVerifier;
	private final OAuthRepository oAuthRepository;
	private final MemberRepository memberRepository;
	private final GuestPromotionService guestPromotionService;
	private final MemberRestrictionRepository memberRestrictionRepository;
	private final RefreshTokenRepository refreshTokenRepository;
	private final NotificationSettingRepository notificationSettingRepository;
	private final JwtProvider jwtProvider;
	private final TokenHasher tokenHasher;
	private final NicknameGenerator nicknameGenerator;

	@Value("${jwt.refresh-token-expiry}")
	private long refreshTokenExpiry;

	@Transactional
	public AppleLoginResponse login(String identityToken, String deviceId) {
		AppleTokenClaims claims = appleTokenVerifier.verify(identityToken);

		// DB에는 providerId가 SHA-256 해시로 저장되어 있으므로, 조회 전에 동일하게 해시 변환
		String hashedProviderId = tokenHasher.hash(claims.sub());

		boolean existsOAuth = oAuthRepository.existsByProviderAndProviderId(OAuthProvider.APPLE, hashedProviderId);

		OAuth oAuth = oAuthRepository
			.findByProviderAndProviderId(OAuthProvider.APPLE, hashedProviderId)
			.orElseGet(() -> registerNewMember(claims, deviceId));

		Member member = oAuth.getMember();

		boolean isBanned = !memberRestrictionRepository.findActiveByMemberIdAndRestrictionTypes(
			member.getId(), List.of(MemberRestrictionType.BANNED), LocalDateTime.now()).isEmpty();
		if (isBanned) {
			throw new MemberException(MemberErrorCode.MEMBER_BANNED);
		}

		boolean isDeletedMember = member.isDeleted();
		Member activeMember;
		if (isDeletedMember) {
			activeMember = memberRepository.save(
				Member.builder()
					.nickname(nicknameGenerator.generate())
					.build());
			oAuth.updateMember(activeMember);
		} else {
			activeMember = member;
		}
		// 신규 가입이거나 탈퇴 후 재가입이면 isNew = true
		boolean isNew = !existsOAuth || isDeletedMember;

		notificationSettingRepository.findByMemberId(activeMember.getId())
			.orElseGet(() -> createDefaultNotificationSetting(activeMember));

		Long memberId = activeMember.getId();
		String accessToken = jwtProvider.generateAccessToken(memberId);
		String refreshToken = jwtProvider.generateRefreshToken(memberId);

		saveRefreshToken(memberId, deviceId, refreshToken);

		return new AppleLoginResponse(accessToken, refreshToken, isNew);
	}

	private OAuth registerNewMember(AppleTokenClaims claims, String deviceId) {
		// 같은 기기의 게스트 계정이 있으면 새로 만들지 않고 그 계정을 승격한다(코스 소유권 유지).
		Member member = guestPromotionService.resolveMemberForNewOAuth(deviceId);

		return oAuthRepository.save(
			OAuth.builder()
				.member(member)
				.provider(OAuthProvider.APPLE)
				// providerId는 SHA-256 해시로 저장
				.providerId(tokenHasher.hash(claims.sub()))
				// email은 EncryptedStringConverter가 자동으로 AES-256 암호화 처리
				.email(claims.email())
				.build());
	}

	private NotificationSetting createDefaultNotificationSetting(Member member) {
		return notificationSettingRepository.save(
			NotificationSetting.builder()
				.member(member)
				.startTime(LocalTime.of(8, 0))
				.endTime(LocalTime.of(21, 0))
				.daysOfWeek("MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY,SATURDAY,SUNDAY")
				.notificationRadius(NotificationRadius.R500)
				.isMarketingEnabled(false)
				.isNotificationEnabled(true)
				.build());
	}

	private void saveRefreshToken(Long memberId, String deviceId, String refreshToken) {
		Member memberRef = memberRepository.getReferenceById(memberId);

		refreshTokenRepository.findByMemberAndDeviceId(memberRef, deviceId).ifPresent(refreshTokenRepository::delete);
		refreshTokenRepository.save(
			RefreshToken.builder()
				.member(memberRef)
				.deviceId(deviceId)
				// 원본 JWT 대신 SHA-256 해시값으로 저장
				.token(tokenHasher.hash(refreshToken))
				.expiredAt(LocalDateTime.now().plusSeconds(refreshTokenExpiry / 1000))
				.build());
	}
}
