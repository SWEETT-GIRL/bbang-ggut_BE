package sweetgirl.croissant.member.service;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.global.encrypt.TokenHasher;
import sweetgirl.croissant.member.code.MemberErrorCode;
import sweetgirl.croissant.member.dto.response.KakaoLoginResponse;
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
public class KakaoAuthService {

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

	// 직접 생성하면 공통 HTTP 설정(타임아웃)이 적용되지 않아 빈으로 주입받는다. KakaoRestClientConfig 참고.
	private final RestTemplate restTemplate;

	@Transactional
	public KakaoLoginResponse login(String kakaoAccessToken, String deviceId, String authorizationHeader) {
		Long callerGuestId = resolveCallerMemberId(authorizationHeader);
		KakaoUserInfo userInfo = fetchUserInfo(kakaoAccessToken);

		// 기존 web OAuth(CustomOAuth2UserService)와 동일한 방식: String.valueOf(id) 후 SHA-256 해시
		String hashedProviderId = tokenHasher.hash(userInfo.providerId());

		boolean existsOAuth = oAuthRepository.existsByProviderAndProviderId(OAuthProvider.KAKAO, hashedProviderId);

		OAuth oAuth = oAuthRepository
			.findByProviderAndProviderId(OAuthProvider.KAKAO, hashedProviderId)
			.orElseGet(() -> registerNewMember(userInfo, hashedProviderId, deviceId, callerGuestId));

		Member member = oAuth.getMember();

		boolean isBanned = !memberRestrictionRepository.findActiveByMemberIdAndRestrictionTypes(
			member.getId(), List.of(MemberRestrictionType.BANNED), LocalDateTime.now()).isEmpty();
		if (isBanned) {
			throw new MemberException(MemberErrorCode.MEMBER_BANNED);
		}

		boolean isDeletedMember = member.isDeleted();
		Member activeMember;
		if (isDeletedMember) {
			activeMember = memberRepository.save(Member.builder().nickname(nicknameGenerator.generate()).build());
			oAuth.updateMember(activeMember);
		} else {
			activeMember = member;
		}
		boolean isNew = !existsOAuth || isDeletedMember;

		// 승격으로 흡수되지 않은 게스트(재로그인·탈퇴 후 재가입)가 있으면 데이터만 옮겨 붙인다.
		// 단, 아직 약관 동의 전(가입 미확정)이면 미룬다 — 동의 취소 시 게스트로 돌아가는
		// 길이 살아 있어야 한다. 미룬 이관은 최초 동의 저장(createConsent) 시점에 한다.
		if (activeMember.isAgreeToTerms()) {
			guestPromotionService.mergeGuestDataInto(activeMember, deviceId, callerGuestId);
		}

		notificationSettingRepository.findByMemberId(activeMember.getId())
			.orElseGet(() -> createDefaultNotificationSetting(activeMember));

		Long memberId = activeMember.getId();
		String accessToken = jwtProvider.generateAccessToken(memberId);
		String refreshToken = jwtProvider.generateRefreshToken(memberId);
		saveRefreshToken(memberId, deviceId, refreshToken);

		return new KakaoLoginResponse(accessToken, refreshToken, isNew);
	}

	@SuppressWarnings("unchecked")
	private KakaoUserInfo fetchUserInfo(String kakaoAccessToken) {
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(kakaoAccessToken);
		Map<String, Object> body;
		try {
			body = restTemplate.exchange(
				"https://kapi.kakao.com/v2/user/me",
				HttpMethod.GET,
				new HttpEntity<>(headers),
				Map.class).getBody();
		} catch (HttpClientErrorException ex) {
			throw new MemberException(MemberErrorCode.SOCIAL_TOKEN_INVALID);
		}
		if (body == null || body.get("id") == null) {
			throw new MemberException(MemberErrorCode.SOCIAL_TOKEN_INVALID);
		}

		String providerId = String.valueOf(body.get("id"));
		String email = null;
		Map<String, Object> kakaoAccount = (Map<String, Object>)body.get("kakao_account");
		if (kakaoAccount != null) {
			email = (String)kakaoAccount.get("email");
		}
		return new KakaoUserInfo(providerId, email);
	}

	private record KakaoUserInfo(String providerId, String email) {
	}

	/** 로그인 요청에 실려 온 (게스트) 액세스 토큰의 주인. 없거나 못 믿을 토큰이면 null. */
	private Long resolveCallerMemberId(String authorizationHeader) {
		if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
			return null;
		}
		String token = authorizationHeader.substring(7);
		if (!jwtProvider.validateToken(token)) {
			return null;
		}
		try {
			return jwtProvider.getMemberId(token);
		} catch (Exception e) {
			return null;
		}
	}

	private OAuth registerNewMember(KakaoUserInfo userInfo, String hashedProviderId, String deviceId,
			Long callerGuestId) {
		// 같은 기기의 게스트 계정이 있으면 새로 만들지 않고 그 계정을 승격한다(코스 소유권 유지).
		Member member = guestPromotionService.resolveMemberForNewOAuth(deviceId, callerGuestId);
		return oAuthRepository.save(OAuth.builder()
			.member(member)
			.provider(OAuthProvider.KAKAO)
			.providerId(hashedProviderId)
			.email(userInfo.email())
			.build());
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
