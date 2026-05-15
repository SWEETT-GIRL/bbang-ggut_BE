package sweetgirl.croissant.member.service;

import java.time.LocalTime;

import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.member.dto.dto.CustomOAuth2User;
import sweetgirl.croissant.member.dto.dto.KakaoOAuth2UserInfo;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.entity.NotificationSetting;
import sweetgirl.croissant.member.entity.OAuth;
import sweetgirl.croissant.member.enums.NotificationRadius;
import sweetgirl.croissant.member.enums.OAuthProvider;
import sweetgirl.croissant.member.repository.MemberRepository;
import sweetgirl.croissant.member.repository.NotificationSettingRepository;
import sweetgirl.croissant.member.repository.OAuthRepository;

@Service
@RequiredArgsConstructor
public class CustomOAuth2UserService extends DefaultOAuth2UserService {

	private final MemberRepository memberRepository;
	private final NotificationSettingRepository notificationSettingRepository;
	private final OAuthRepository oAuthRepository;

	// Spring Security가 카카오 사용자 정보를 받아온 직후 자동으로 호출하는 메서드
	// 여기서 DB upsert(신규 가입 or 기존 유저 조회)를 처리하고 인증된 유저 정보를 반환
	@Override
	@Transactional
	public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {

		// 부모 클래스(DefaultOAuth2UserService)가 카카오 서버에서 사용자 정보를 가져옴
		// 내부적으로 카카오 User Info API(https://kapi.kakao.com/v2/user/me)를 호출
		OAuth2User oAuth2User = super.loadUser(userRequest);

		// 카카오 응답 JSON을 파싱해서 필요한 값(providerId, email, nickname)만 추출
		KakaoOAuth2UserInfo userInfo = new KakaoOAuth2UserInfo(oAuth2User.getAttributes());

		boolean existsOAuth = oAuthRepository.existsByProviderAndProviderId(OAuthProvider.KAKAO,
			userInfo.getProviderId());

		// DB에서 provider(KAKAO) + providerId 조합으로 기존 OAuth 레코드 조회
		// 없으면 신규 가입, 있으면 기존 유저
		OAuth oAuth = oAuthRepository //Oauth 객체 반환
			.findByProviderAndProviderId(OAuthProvider.KAKAO, userInfo.getProviderId())
			.orElseGet(() -> registerNewMember(userInfo)); // 없으면 신규 가입 처리

		Member member = oAuth.getMember();
		boolean isDeletedMember = member.isDeleted();
		if (isDeletedMember) {
			member.reactivate();
		}
		// 신규 가입이거나 탈퇴 후 재가입이면 isNew = true
		Boolean isNewMember = !existsOAuth || isDeletedMember;

		notificationSettingRepository.findByMemberId(member.getId())
			.orElseGet(() -> createDefaultNotificationSetting(member));

		// CustomOAuth2User로 감싸서 반환
		// 카카오 원본 OAuth2User + 우리 DB의 memberId를 함께 보관
		// OAuth2SuccessHandler에서 authentication.getPrincipal()로 꺼내 JWT 생성에 사용
		return new CustomOAuth2User(oAuth2User, member.getId(), isNewMember);
	}

	// 신규 가입 처리: Member 생성 → OAuth 생성 → 저장
	private OAuth registerNewMember(KakaoOAuth2UserInfo userInfo) {

		// 카카오 닉네임으로 Member 생성 및 저장
		Member member = memberRepository.save(
			Member.builder()
				.nickname(userInfo.getNickname())
				.build());

		// Member와 카카오 정보를 연결하는 OAuth 레코드 생성 및 저장
		OAuth oAuth = oAuthRepository.save(
			OAuth.builder()
				.member(member)
				.provider(OAuthProvider.KAKAO) // 소셜 로그인 제공자 (KAKAO)
				.providerId(userInfo.getProviderId()) // 카카오 고유 유저 ID
				.email(userInfo.getEmail()) // 카카오 계정 이메일
				.build());
		return oAuth;
	}

	private NotificationSetting createDefaultNotificationSetting(Member member) {
		NotificationSetting setting = notificationSettingRepository.save(
			NotificationSetting.builder()
				.member(member)
				.startTime(LocalTime.of(8, 0))
				.endTime(LocalTime.of(21, 0))
				.daysOfWeek("MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY,SATURDAY,SUNDAY")
				.notificationRadius(NotificationRadius.R500)
				.isMarketingEnabled(false)
				.isNotificationEnabled(true)
				.build());

		return setting;
	}

}
