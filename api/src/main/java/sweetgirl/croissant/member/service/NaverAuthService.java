package sweetgirl.croissant.member.service;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.global.encrypt.TokenHasher;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.entity.NotificationSetting;
import sweetgirl.croissant.member.entity.OAuth;
import sweetgirl.croissant.member.enums.MemberRestrictionType;
import sweetgirl.croissant.member.enums.NotificationRadius;
import sweetgirl.croissant.member.enums.OAuthProvider;
import sweetgirl.croissant.member.event.MemberSignedUpEvent;
import sweetgirl.croissant.member.repository.MemberRepository;
import sweetgirl.croissant.member.repository.MemberRestrictionRepository;
import sweetgirl.croissant.member.repository.NotificationSettingRepository;
import sweetgirl.croissant.member.repository.OAuthRepository;
import sweetgirl.croissant.member.util.NicknameGenerator;

/**
 * 네이버 로그인의 회원 처리. 네이버는 SDK 없이 웹 OAuth(서버 리다이렉트)로만 들어오므로
 * CustomOAuth2UserService가 네이버 사용자 정보를 받은 직후 호출한다.
 *
 * 회원 처리 규칙은 KakaoAuthService.login과 같다(게스트 승격·데이터 이관·가입 편지).
 * 다만 브라우저 리다이렉트라 Authorization 헤더가 없어, 게스트는 deviceId로만 찾는다.
 * 토큰 발급은 OAuth2SuccessHandler가 한다.
 */
@Service
@RequiredArgsConstructor
public class NaverAuthService {

	private final OAuthRepository oAuthRepository;
	private final MemberRepository memberRepository;
	private final GuestPromotionService guestPromotionService;
	private final MemberRestrictionRepository memberRestrictionRepository;
	private final NotificationSettingRepository notificationSettingRepository;
	private final TokenHasher tokenHasher;
	private final NicknameGenerator nicknameGenerator;
	private final ApplicationEventPublisher eventPublisher;

	@Transactional
	public NaverLoginResult login(String providerId, String email, String deviceId) {
		String hashedProviderId = tokenHasher.hash(providerId);

		boolean existsOAuth = oAuthRepository.existsByProviderAndProviderId(OAuthProvider.NAVER, hashedProviderId);

		OAuth oAuth = oAuthRepository
			.findByProviderAndProviderId(OAuthProvider.NAVER, hashedProviderId)
			.orElseGet(() -> registerNewMember(hashedProviderId, email, deviceId));

		Member member = oAuth.getMember();

		boolean isBanned = !memberRestrictionRepository.findActiveByMemberIdAndRestrictionTypes(
			member.getId(), List.of(MemberRestrictionType.BANNED), LocalDateTime.now()).isEmpty();
		if (isBanned) {
			// 웹 OAuth 흐름이라 SecurityConfig의 failureHandler가 이 코드를 딥링크로 전달한다.
			throw new OAuth2AuthenticationException(
				new OAuth2Error("MEMBER_BANNED", "강퇴된 회원입니다.", null));
		}

		boolean isDeletedMember = member.isDeleted();
		Member activeMember;
		if (isDeletedMember) {
			activeMember = memberRepository.save(
				Member.rejoining(nicknameGenerator.generate(), member.getId(), LocalDateTime.now()));
			oAuth.updateMember(activeMember);
		} else {
			activeMember = member;
		}
		boolean isNew = !existsOAuth || isDeletedMember;

		// 약관 동의 전(가입 미확정)이면 미룬다 — KakaoAuthService와 같은 이유.
		if (activeMember.isAgreeToTerms()) {
			guestPromotionService.mergeGuestDataInto(activeMember, deviceId, null);
		}

		notificationSettingRepository.findByMemberId(activeMember.getId())
			.orElseGet(() -> createDefaultNotificationSetting(activeMember));

		Long memberId = activeMember.getId();
		if (isNew) {
			eventPublisher.publishEvent(new MemberSignedUpEvent(memberId));
		}

		return new NaverLoginResult(memberId, isNew);
	}

	public record NaverLoginResult(Long memberId, boolean isNew) {
	}

	private OAuth registerNewMember(String hashedProviderId, String email, String deviceId) {
		// 같은 기기의 게스트 계정이 있으면 새로 만들지 않고 그 계정을 승격한다(코스 소유권 유지).
		Member member = guestPromotionService.resolveMemberForNewOAuth(deviceId, null);
		return oAuthRepository.save(OAuth.builder()
			.member(member)
			.provider(OAuthProvider.NAVER)
			.providerId(hashedProviderId)
			.email(email)
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
}
