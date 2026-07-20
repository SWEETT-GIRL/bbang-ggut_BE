package sweetgirl.croissant.member.service;

import java.time.LocalDateTime;
import java.time.LocalTime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.global.encrypt.TokenHasher;
import sweetgirl.croissant.member.dto.response.GuestLoginResponse;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.entity.NotificationSetting;
import sweetgirl.croissant.member.entity.RefreshToken;
import sweetgirl.croissant.member.enums.NotificationRadius;
import sweetgirl.croissant.member.jwt.JwtProvider;
import sweetgirl.croissant.member.repository.MemberRepository;
import sweetgirl.croissant.member.repository.NotificationSettingRepository;
import sweetgirl.croissant.member.repository.RefreshTokenRepository;
import sweetgirl.croissant.member.util.NicknameGenerator;

/**
 * 로그인 없이 사용하는 게스트 인증.
 * 소셜 로그인(KakaoAuthService/AppleAuthService)과 달리 OAuth 연결 없이
 * 익명 Member를 생성하고 랜덤 닉네임 + JWT를 발급한다.
 * 클라이언트는 첫 실행 시 1회 호출해 토큰을 저장하고 이후 refresh로 재사용한다.
 */
@Service
@RequiredArgsConstructor
public class GuestAuthService {

	private final MemberRepository memberRepository;
	private final RefreshTokenRepository refreshTokenRepository;
	private final NotificationSettingRepository notificationSettingRepository;
	private final JwtProvider jwtProvider;
	private final TokenHasher tokenHasher;
	private final NicknameGenerator nicknameGenerator;

	@Value("${jwt.refresh-token-expiry}")
	private long refreshTokenExpiry;

	@Transactional
	public GuestLoginResponse login(String deviceId) {
		Member member = Member.builder().nickname(nicknameGenerator.generate()).build();
		member.markAsGuest();
		Member savedMember = memberRepository.save(member);
		Long memberId = savedMember.getId();

		notificationSettingRepository.findByMemberId(memberId)
			.orElseGet(() -> createDefaultNotificationSetting(savedMember));

		String accessToken = jwtProvider.generateAccessToken(memberId);
		String refreshToken = jwtProvider.generateRefreshToken(memberId);
		saveRefreshToken(memberId, deviceId, refreshToken);

		return new GuestLoginResponse(accessToken, refreshToken, memberId, savedMember.getNickname());
	}

	private NotificationSetting createDefaultNotificationSetting(Member member) {
		return notificationSettingRepository.save(NotificationSetting.builder()
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
		refreshTokenRepository.save(RefreshToken.builder()
			.member(memberRef)
			.deviceId(deviceId)
			.token(tokenHasher.hash(refreshToken))
			.expiredAt(LocalDateTime.now().plusSeconds(refreshTokenExpiry / 1000))
			.build());
	}
}
