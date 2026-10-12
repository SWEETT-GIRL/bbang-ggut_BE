package sweetgirl.croissant.member.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;

import sweetgirl.croissant.global.encrypt.TokenHasher;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.entity.MemberRestriction;
import sweetgirl.croissant.member.entity.NotificationSetting;
import sweetgirl.croissant.member.entity.OAuth;
import sweetgirl.croissant.member.enums.OAuthProvider;
import sweetgirl.croissant.member.event.MemberSignedUpEvent;
import sweetgirl.croissant.member.repository.MemberRepository;
import sweetgirl.croissant.member.repository.MemberRestrictionRepository;
import sweetgirl.croissant.member.repository.NotificationSettingRepository;
import sweetgirl.croissant.member.repository.OAuthRepository;
import sweetgirl.croissant.member.util.NicknameGenerator;

@ExtendWith(MockitoExtension.class)
class NaverAuthServiceTest {

	@Mock
	private OAuthRepository oAuthRepository;
	@Mock
	private MemberRepository memberRepository;
	@Mock
	private GuestPromotionService guestPromotionService;
	@Mock
	private MemberRestrictionRepository memberRestrictionRepository;
	@Mock
	private NotificationSettingRepository notificationSettingRepository;
	@Mock
	private TokenHasher tokenHasher;
	@Mock
	private NicknameGenerator nicknameGenerator;
	@Mock
	private ApplicationEventPublisher eventPublisher;

	@InjectMocks
	private NaverAuthService naverAuthService;

	private static final String PROVIDER_ID = "naver-id";
	private static final String HASHED_ID = "hashed-naver-id";
	private static final String DEVICE_ID = "device-1";

	// 신규 가입 — 같은 기기의 게스트를 승격하고 가입 이벤트를 낸다
	@Test
	void login_promotesGuestAndPublishesSignUp_whenNotRegistered() {
		given(tokenHasher.hash(PROVIDER_ID)).willReturn(HASHED_ID);
		given(oAuthRepository.existsByProviderAndProviderId(OAuthProvider.NAVER, HASHED_ID)).willReturn(false);
		given(oAuthRepository.findByProviderAndProviderId(OAuthProvider.NAVER, HASHED_ID))
			.willReturn(Optional.empty());

		Member promoted = memberOf(1L);
		given(guestPromotionService.resolveMemberForNewOAuth(DEVICE_ID, null)).willReturn(promoted);
		given(oAuthRepository.save(any(OAuth.class))).willAnswer(invocation -> invocation.getArgument(0));
		given(memberRestrictionRepository.findActiveByMemberIdAndRestrictionTypes(eq(1L), any(), any()))
			.willReturn(List.of());
		given(notificationSettingRepository.findByMemberId(1L))
			.willReturn(Optional.of(mock(NotificationSetting.class)));

		NaverAuthService.NaverLoginResult result = naverAuthService.login(PROVIDER_ID, "a@naver.com", DEVICE_ID);

		assertThat(result.memberId()).isEqualTo(1L);
		assertThat(result.isNew()).isTrue();
		then(eventPublisher).should().publishEvent(new MemberSignedUpEvent(1L));
		// 약관 동의 전이라 게스트 데이터 이관은 미룬다
		then(guestPromotionService).should(never()).mergeGuestDataInto(any(), any(), any());
	}

	// 기존 회원 — 새로 만들지 않고, 게스트가 쌓아둔 데이터를 옮겨 붙인다
	@Test
	void login_returnsExistingMemberAndMergesGuestData_whenAlreadyRegistered() {
		given(tokenHasher.hash(PROVIDER_ID)).willReturn(HASHED_ID);
		given(oAuthRepository.existsByProviderAndProviderId(OAuthProvider.NAVER, HASHED_ID)).willReturn(true);

		Member existing = memberOf(2L);
		given(existing.isAgreeToTerms()).willReturn(true);
		OAuth oAuth = mock(OAuth.class);
		given(oAuth.getMember()).willReturn(existing);
		given(oAuthRepository.findByProviderAndProviderId(OAuthProvider.NAVER, HASHED_ID))
			.willReturn(Optional.of(oAuth));
		given(memberRestrictionRepository.findActiveByMemberIdAndRestrictionTypes(eq(2L), any(), any()))
			.willReturn(List.of());
		given(notificationSettingRepository.findByMemberId(2L))
			.willReturn(Optional.of(mock(NotificationSetting.class)));

		NaverAuthService.NaverLoginResult result = naverAuthService.login(PROVIDER_ID, "a@naver.com", DEVICE_ID);

		assertThat(result.memberId()).isEqualTo(2L);
		assertThat(result.isNew()).isFalse();
		then(guestPromotionService).should().mergeGuestDataInto(existing, DEVICE_ID, null);
		then(guestPromotionService).should(never()).resolveMemberForNewOAuth(any(), any());
		then(eventPublisher).shouldHaveNoInteractions();
	}

	// 강퇴 회원 — 웹 OAuth 실패 핸들러가 읽는 MEMBER_BANNED 코드로 막는다
	@Test
	void login_throwsMemberBanned_whenMemberIsBanned() {
		given(tokenHasher.hash(PROVIDER_ID)).willReturn(HASHED_ID);
		given(oAuthRepository.existsByProviderAndProviderId(OAuthProvider.NAVER, HASHED_ID)).willReturn(true);

		Member banned = memberOf(3L);
		OAuth oAuth = mock(OAuth.class);
		given(oAuth.getMember()).willReturn(banned);
		given(oAuthRepository.findByProviderAndProviderId(OAuthProvider.NAVER, HASHED_ID))
			.willReturn(Optional.of(oAuth));
		given(memberRestrictionRepository.findActiveByMemberIdAndRestrictionTypes(eq(3L), any(), any()))
			.willReturn(List.of(mock(MemberRestriction.class)));

		assertThatThrownBy(() -> naverAuthService.login(PROVIDER_ID, "a@naver.com", DEVICE_ID))
			.isInstanceOfSatisfying(OAuth2AuthenticationException.class,
				ex -> assertThat(ex.getError().getErrorCode()).isEqualTo("MEMBER_BANNED"));
		then(eventPublisher).shouldHaveNoInteractions();
	}

	private Member memberOf(Long id) {
		Member member = mock(Member.class);
		given(member.getId()).willReturn(id);
		return member;
	}
}
