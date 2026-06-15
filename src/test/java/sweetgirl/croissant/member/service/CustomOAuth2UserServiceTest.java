package sweetgirl.croissant.member.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.core.user.OAuth2User;

import sweetgirl.croissant.global.encrypt.TokenHasher;
import sweetgirl.croissant.member.dto.dto.CustomOAuth2User;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.entity.NotificationSetting;
import sweetgirl.croissant.member.entity.OAuth;
import sweetgirl.croissant.member.enums.OAuthProvider;
import sweetgirl.croissant.member.repository.MemberRepository;
import sweetgirl.croissant.member.repository.MemberRestrictionRepository;
import sweetgirl.croissant.member.repository.NotificationSettingRepository;
import sweetgirl.croissant.member.repository.OAuthRepository;
import sweetgirl.croissant.member.util.NicknameGenerator;

@ExtendWith(MockitoExtension.class)
class CustomOAuth2UserServiceTest {

	@Mock
	private MemberRepository memberRepository;
	@Mock
	private NotificationSettingRepository notificationSettingRepository;
	@Mock
	private OAuthRepository oAuthRepository;
	@Mock
	private MemberRestrictionRepository memberRestrictionRepository;
	@Mock
	private TokenHasher tokenHasher;
	@Mock
	private NicknameGenerator nicknameGenerator;

	@InjectMocks
	private CustomOAuth2UserService customOAuth2UserService;

	private static final String PROVIDER_ID = "123456";
	private static final String HASHED_ID = "hashed-provider-id";
	private static final String GENERATED_NICKNAME = "달콤한 망고시루";

	// 신규 가입
	@Test
	void processOAuth2User_createsNewMemberWithGeneratedNickname_whenNotRegistered() {
		OAuth2User oAuth2User = kakaoOAuth2User(PROVIDER_ID);
		given(tokenHasher.hash(PROVIDER_ID)).willReturn(HASHED_ID);
		given(oAuthRepository.existsByProviderAndProviderId(OAuthProvider.KAKAO, HASHED_ID)).willReturn(false);
		given(oAuthRepository.findByProviderAndProviderId(OAuthProvider.KAKAO, HASHED_ID)).willReturn(
			Optional.empty());
		given(nicknameGenerator.generate()).willReturn(GENERATED_NICKNAME);

		Member savedMember = memberOf(1L);
		given(savedMember.isDeleted()).willReturn(false);
		given(memberRepository.save(any(Member.class))).willReturn(savedMember);

		OAuth savedOAuth = oauthWith(savedMember);
		given(oAuthRepository.save(any(OAuth.class))).willReturn(savedOAuth);
		given(memberRestrictionRepository.findActiveByMemberIdAndRestrictionTypes(eq(1L), any(), any()))
			.willReturn(List.of());
		given(notificationSettingRepository.findByMemberId(1L)).willReturn(
			Optional.of(mock(NotificationSetting.class)));

		CustomOAuth2User result = (CustomOAuth2User)customOAuth2UserService.processOAuth2User(oAuth2User);

		assertThat(result.getMemberId()).isEqualTo(1L);
		assertThat(result.getIsNewMember()).isTrue();
		then(nicknameGenerator).should().generate();
	}

	// 기존 유저
	@Test
	void processOAuth2User_returnsExistingMember_whenAlreadyRegistered() {
		OAuth2User oAuth2User = kakaoOAuth2User(PROVIDER_ID);
		given(tokenHasher.hash(PROVIDER_ID)).willReturn(HASHED_ID);
		given(oAuthRepository.existsByProviderAndProviderId(OAuthProvider.KAKAO, HASHED_ID)).willReturn(true);

		Member existingMember = memberOf(2L);
		given(existingMember.isDeleted()).willReturn(false);
		OAuth existingOAuth = oauthWith(existingMember);
		given(oAuthRepository.findByProviderAndProviderId(OAuthProvider.KAKAO, HASHED_ID)).willReturn(
			Optional.of(existingOAuth));
		given(memberRestrictionRepository.findActiveByMemberIdAndRestrictionTypes(eq(2L), any(), any()))
			.willReturn(List.of());
		given(notificationSettingRepository.findByMemberId(2L)).willReturn(
			Optional.of(mock(NotificationSetting.class)));

		CustomOAuth2User result = (CustomOAuth2User)customOAuth2UserService.processOAuth2User(oAuth2User);

		assertThat(result.getMemberId()).isEqualTo(2L);
		assertThat(result.getIsNewMember()).isFalse();
		then(nicknameGenerator).shouldHaveNoInteractions();
		then(memberRepository).should(never()).save(any(Member.class));
	}

	// 탈퇴 후 재가입
	@Test
	void processOAuth2User_createsNewMember_whenPreviouslyDeleted() {
		OAuth2User oAuth2User = kakaoOAuth2User(PROVIDER_ID);
		given(tokenHasher.hash(PROVIDER_ID)).willReturn(HASHED_ID);
		given(oAuthRepository.existsByProviderAndProviderId(OAuthProvider.KAKAO, HASHED_ID)).willReturn(true);
		given(nicknameGenerator.generate()).willReturn(GENERATED_NICKNAME);

		Member deletedMember = mock(Member.class);
		given(deletedMember.isDeleted()).willReturn(true);
		OAuth existingOAuth = mock(OAuth.class);
		given(existingOAuth.getMember()).willReturn(deletedMember);
		given(oAuthRepository.findByProviderAndProviderId(OAuthProvider.KAKAO, HASHED_ID)).willReturn(
			Optional.of(existingOAuth));
		given(deletedMember.getId()).willReturn(3L);
		given(memberRestrictionRepository.findActiveByMemberIdAndRestrictionTypes(eq(3L), any(), any()))
			.willReturn(List.of());

		Member newMember = memberOf(3L);
		given(memberRepository.save(any(Member.class))).willReturn(newMember);
		given(notificationSettingRepository.findByMemberId(3L)).willReturn(
			Optional.of(mock(NotificationSetting.class)));

		CustomOAuth2User result = (CustomOAuth2User)customOAuth2UserService.processOAuth2User(oAuth2User);

		assertThat(result.getMemberId()).isEqualTo(3L);
		assertThat(result.getIsNewMember()).isTrue();
		then(nicknameGenerator).should().generate();
		then(existingOAuth).should().updateMember(newMember);
	}

	private OAuth2User kakaoOAuth2User(String providerId) {
		Map<String, Object> profile = Map.of("nickname", "카카오닉네임");
		Map<String, Object> kakaoAccount = Map.of("email", "test@test.com", "profile", profile);
		Map<String, Object> attributes = Map.of("id", providerId, "kakao_account", kakaoAccount);

		OAuth2User oAuth2User = mock(OAuth2User.class);
		given(oAuth2User.getAttributes()).willReturn(attributes);
		return oAuth2User;
	}

	private Member memberOf(Long id) {
		Member member = mock(Member.class);
		given(member.getId()).willReturn(id);
		return member;
	}

	private OAuth oauthWith(Member member) {
		OAuth oAuth = mock(OAuth.class);
		given(oAuth.getMember()).willReturn(member);
		return oAuth;
	}
}
