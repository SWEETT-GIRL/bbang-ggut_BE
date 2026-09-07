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

import sweetgirl.croissant.global.encrypt.TokenHasher;
import sweetgirl.croissant.member.dto.dto.AppleTokenClaims;
import sweetgirl.croissant.member.dto.response.AppleLoginResponse;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.entity.NotificationSetting;
import sweetgirl.croissant.member.entity.OAuth;
import sweetgirl.croissant.member.enums.OAuthProvider;
import sweetgirl.croissant.member.jwt.JwtProvider;
import sweetgirl.croissant.member.repository.MemberRepository;
import sweetgirl.croissant.member.repository.MemberRestrictionRepository;
import sweetgirl.croissant.member.repository.NotificationSettingRepository;
import sweetgirl.croissant.member.repository.OAuthRepository;
import sweetgirl.croissant.member.repository.RefreshTokenRepository;
import sweetgirl.croissant.member.util.NicknameGenerator;

@ExtendWith(MockitoExtension.class)
class AppleAuthServiceTest {

	@Mock
	private AppleTokenVerifier appleTokenVerifier;
	@Mock
	private OAuthRepository oAuthRepository;
	@Mock
	private MemberRepository memberRepository;
	@Mock
	private GuestPromotionService guestPromotionService;
	@Mock
	private MemberRestrictionRepository memberRestrictionRepository;
	@Mock
	private RefreshTokenRepository refreshTokenRepository;
	@Mock
	private NotificationSettingRepository notificationSettingRepository;
	@Mock
	private JwtProvider jwtProvider;
	@Mock
	private TokenHasher tokenHasher;
	@Mock
	private NicknameGenerator nicknameGenerator;

	@Mock
	private ApplicationEventPublisher eventPublisher;

	@InjectMocks
	private AppleAuthService appleAuthService;

	private static final String IDENTITY_TOKEN = "apple.identity.token";
	private static final String SUB = "apple-user-sub";
	private static final String HASHED_SUB = "hashed-sub";
	private static final String DEVICE_ID = "device-001";
	private static final String GENERATED_NICKNAME = "신난 두쫀쿠";

	// 신규 가입 — 붙일 Member는 GuestPromotionService가 정한다(게스트 승격 또는 신규 생성)
	@Test
	void login_attachesOAuthToResolvedMember_whenNotRegistered() {
		given(appleTokenVerifier.verify(IDENTITY_TOKEN)).willReturn(new AppleTokenClaims(SUB, "test@apple.com"));
		given(tokenHasher.hash(anyString())).willReturn("some-hash");
		given(tokenHasher.hash(SUB)).willReturn(HASHED_SUB);
		given(oAuthRepository.existsByProviderAndProviderId(OAuthProvider.APPLE, HASHED_SUB)).willReturn(false);
		given(oAuthRepository.findByProviderAndProviderId(OAuthProvider.APPLE, HASHED_SUB)).willReturn(
			Optional.empty());

		Member resolvedMember = memberOf(1L);
		given(resolvedMember.isDeleted()).willReturn(false);
		given(guestPromotionService.resolveMemberForNewOAuth(DEVICE_ID, null)).willReturn(resolvedMember);
		given(memberRepository.getReferenceById(1L)).willReturn(resolvedMember);

		OAuth savedOAuth = oauthWith(resolvedMember);
		given(oAuthRepository.save(any(OAuth.class))).willReturn(savedOAuth);
		given(memberRestrictionRepository.findActiveByMemberIdAndRestrictionTypes(eq(1L), any(), any()))
			.willReturn(List.of());
		given(notificationSettingRepository.findByMemberId(1L)).willReturn(
			Optional.of(mock(NotificationSetting.class)));
		given(jwtProvider.generateAccessToken(1L)).willReturn("access-token");
		given(jwtProvider.generateRefreshToken(1L)).willReturn("refresh-token");

		AppleLoginResponse response = appleAuthService.login(IDENTITY_TOKEN, DEVICE_ID, null);

		assertThat(response.accessToken()).isEqualTo("access-token");
		assertThat(response.isNew()).isTrue();
		then(guestPromotionService).should().resolveMemberForNewOAuth(DEVICE_ID, null);
		// 신규 Member 생성 책임은 GuestPromotionService로 옮겨졌다
		then(memberRepository).should(never()).save(any(Member.class));
	}

	// 기존 유저
	@Test
	void login_returnsTokens_whenAlreadyRegistered() {
		given(appleTokenVerifier.verify(IDENTITY_TOKEN)).willReturn(new AppleTokenClaims(SUB, "test@apple.com"));
		given(tokenHasher.hash(anyString())).willReturn("some-hash");
		given(tokenHasher.hash(SUB)).willReturn(HASHED_SUB);
		given(oAuthRepository.existsByProviderAndProviderId(OAuthProvider.APPLE, HASHED_SUB)).willReturn(true);

		Member existingMember = memberOf(2L);
		given(existingMember.isDeleted()).willReturn(false);
		OAuth existingOAuth = oauthWith(existingMember);
		given(oAuthRepository.findByProviderAndProviderId(OAuthProvider.APPLE, HASHED_SUB)).willReturn(
			Optional.of(existingOAuth));
		given(memberRestrictionRepository.findActiveByMemberIdAndRestrictionTypes(eq(2L), any(), any()))
			.willReturn(List.of());
		given(memberRepository.getReferenceById(2L)).willReturn(existingMember);
		given(notificationSettingRepository.findByMemberId(2L)).willReturn(
			Optional.of(mock(NotificationSetting.class)));
		given(jwtProvider.generateAccessToken(2L)).willReturn("access-token");
		given(jwtProvider.generateRefreshToken(2L)).willReturn("refresh-token");

		AppleLoginResponse response = appleAuthService.login(IDENTITY_TOKEN, DEVICE_ID, null);

		assertThat(response.accessToken()).isEqualTo("access-token");
		assertThat(response.isNew()).isFalse();
		then(nicknameGenerator).shouldHaveNoInteractions();
		then(memberRepository).should(never()).save(any(Member.class));
	}

	// 탈퇴 후 재가입
	@Test
	void login_createsNewMember_whenPreviouslyDeleted() {
		given(appleTokenVerifier.verify(IDENTITY_TOKEN)).willReturn(new AppleTokenClaims(SUB, "test@apple.com"));
		given(tokenHasher.hash(anyString())).willReturn("some-hash");
		given(tokenHasher.hash(SUB)).willReturn(HASHED_SUB);
		given(oAuthRepository.existsByProviderAndProviderId(OAuthProvider.APPLE, HASHED_SUB)).willReturn(true);
		given(nicknameGenerator.generate()).willReturn(GENERATED_NICKNAME);

		Member deletedMember = mock(Member.class);
		given(deletedMember.isDeleted()).willReturn(true);
		OAuth existingOAuth = mock(OAuth.class);
		given(existingOAuth.getMember()).willReturn(deletedMember);
		given(oAuthRepository.findByProviderAndProviderId(OAuthProvider.APPLE, HASHED_SUB)).willReturn(
			Optional.of(existingOAuth));
		given(deletedMember.getId()).willReturn(3L);
		given(memberRestrictionRepository.findActiveByMemberIdAndRestrictionTypes(eq(3L), any(), any()))
			.willReturn(List.of());

		Member newMember = memberOf(3L);
		given(memberRepository.save(any(Member.class))).willReturn(newMember);
		given(memberRepository.getReferenceById(3L)).willReturn(newMember);
		given(notificationSettingRepository.findByMemberId(3L)).willReturn(
			Optional.of(mock(NotificationSetting.class)));
		given(jwtProvider.generateAccessToken(3L)).willReturn("access-token");
		given(jwtProvider.generateRefreshToken(3L)).willReturn("refresh-token");

		AppleLoginResponse response = appleAuthService.login(IDENTITY_TOKEN, DEVICE_ID, null);

		assertThat(response.isNew()).isTrue();
		then(nicknameGenerator).should().generate();
		then(existingOAuth).should().updateMember(newMember);
		// 아직 동의 전이므로 게스트 이관은 미뤄진다 — 여기서 이관하면 동의 취소 시
		// 게스트 세션이 이미 닫혀 410으로 온보딩에 튕긴다.
		then(guestPromotionService).should(never()).mergeGuestDataInto(any(), any(), any());
	}

	// 이미 동의를 마친 기존 회원의 재로그인 — 게스트 데이터는 로그인 즉시 이관한다
	@Test
	void login_mergesGuestData_whenAlreadyConsented() {
		given(appleTokenVerifier.verify(IDENTITY_TOKEN)).willReturn(new AppleTokenClaims(SUB, "test@apple.com"));
		given(tokenHasher.hash(anyString())).willReturn("some-hash");
		given(tokenHasher.hash(SUB)).willReturn(HASHED_SUB);
		given(oAuthRepository.existsByProviderAndProviderId(OAuthProvider.APPLE, HASHED_SUB)).willReturn(true);

		Member existingMember = memberOf(2L);
		given(existingMember.isDeleted()).willReturn(false);
		given(existingMember.isAgreeToTerms()).willReturn(true);
		OAuth existingOAuth = oauthWith(existingMember);
		given(oAuthRepository.findByProviderAndProviderId(OAuthProvider.APPLE, HASHED_SUB)).willReturn(
			Optional.of(existingOAuth));
		given(memberRestrictionRepository.findActiveByMemberIdAndRestrictionTypes(eq(2L), any(), any()))
			.willReturn(List.of());
		given(memberRepository.getReferenceById(2L)).willReturn(existingMember);
		given(notificationSettingRepository.findByMemberId(2L)).willReturn(
			Optional.of(mock(NotificationSetting.class)));
		given(jwtProvider.generateAccessToken(2L)).willReturn("access-token");
		given(jwtProvider.generateRefreshToken(2L)).willReturn("refresh-token");

		appleAuthService.login(IDENTITY_TOKEN, DEVICE_ID, null);

		then(guestPromotionService).should().mergeGuestDataInto(existingMember, DEVICE_ID, null);
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
