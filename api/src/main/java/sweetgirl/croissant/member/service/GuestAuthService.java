package sweetgirl.croissant.member.service;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.global.encrypt.TokenHasher;
import sweetgirl.croissant.member.dto.response.GuestLoginResponse;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.entity.NotificationSetting;
import sweetgirl.croissant.member.entity.RefreshToken;
import sweetgirl.croissant.member.enums.NotificationRadius;
import sweetgirl.croissant.member.event.MemberSignedUpEvent;
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
	private final ApplicationEventPublisher eventPublisher;

	@Value("${jwt.refresh-token-expiry}")
	private long refreshTokenExpiry;

	@Transactional
	public GuestLoginResponse login(String deviceId) {
		lockDevice(deviceId);

		// 같은 기기(deviceId)의 기존 게스트가 있으면 재사용, 없으면 새로 생성.
		// 앱 재설치/토큰 만료 뒤에도 신원(=코스 소유권)이 유지된다.
		Optional<Member> existingGuest = findExistingGuest(deviceId);
		Member member = existingGuest.orElseGet(this::createGuest);
		Long memberId = member.getId();

		notificationSettingRepository.findByMemberId(memberId)
			.orElseGet(() -> createDefaultNotificationSetting(member));

		String accessToken = jwtProvider.generateAccessToken(memberId);
		String refreshToken = jwtProvider.generateRefreshToken(memberId);
		saveRefreshToken(memberId, deviceId, refreshToken);
		// 첫 발급일 때만 인사 편지. 재사용된 게스트는 이미 편지함을 갖고 있다.
		if (existingGuest.isEmpty()) {
			eventPublisher.publishEvent(new MemberSignedUpEvent(memberId));
		}

		return new GuestLoginResponse(accessToken, refreshToken, memberId, member.getNickname());
	}

	/**
	 * 같은 기기의 발급 요청이 겹치면 둘 다 "기존 게스트 없음"을 보고 각자 게스트를 만든다 —
	 * prod에 0.04초 차 쌍둥이 게스트가 실제로 남았다(기기 4대). 조회~생성 구간을 기기 단위로
	 * 직렬화해 막는다. deviceId를 특정할 수 없으면 재사용 대상 자체가 없어 잠글 것도 없다.
	 */
	private void lockDevice(String deviceId) {
		if (deviceId == null || deviceId.isBlank()) {
			return;
		}
		refreshTokenRepository.lockGuestDevice(deviceId.hashCode());
	}

	private Optional<Member> findExistingGuest(String deviceId) {
		if (deviceId == null || deviceId.isBlank()) {
			return Optional.empty();
		}
		return refreshTokenRepository.findGuestTokensByDeviceId(deviceId).stream()
			.map(RefreshToken::getMember)
			.findFirst();
	}

	private Member createGuest() {
		Member member = Member.builder().nickname(nicknameGenerator.generate()).build();
		member.markAsGuest();
		return memberRepository.save(member);
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
		refreshTokenRepository.deleteByMemberIdAndDeviceId(memberId, deviceId);
		refreshTokenRepository.save(RefreshToken.builder()
			.member(memberRef)
			.deviceId(deviceId)
			.token(tokenHasher.hash(refreshToken))
			.expiredAt(LocalDateTime.now().plusSeconds(refreshTokenExpiry / 1000))
			.build());
	}
}
