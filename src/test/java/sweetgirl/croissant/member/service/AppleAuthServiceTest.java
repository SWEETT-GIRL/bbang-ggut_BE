package sweetgirl.croissant.member.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.global.encrypt.TokenHasher;
import sweetgirl.croissant.member.dto.dto.AppleTokenClaims;
import sweetgirl.croissant.member.dto.response.AppleLoginResponse;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.entity.NotificationSetting;
import sweetgirl.croissant.member.entity.OAuth;
import sweetgirl.croissant.member.enums.OAuthProvider;
import sweetgirl.croissant.member.jwt.JwtProvider;
import sweetgirl.croissant.member.repository.MemberRepository;
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
	private RefreshTokenRepository refreshTokenRepository;
	@Mock
	private NotificationSettingRepository notificationSettingRepository;
	@Mock
	private JwtProvider jwtProvider;
	@Mock
	private TokenHasher tokenHasher;
	@Mock
	private NicknameGenerator nicknameGenerator;

	@InjectMocks
	private AppleAuthService appleAuthService;

	private static final String IDENTITY_TOKEN = "apple.identity.token";
	private static final String SUB = "apple-user-sub";
	private static final String HASHED_SUB = "hashed-sub";
	private static final String DEVICE_ID = "device-001";
	private static final String GENERATED_NICKNAME = "신난 두쫀쿠";

	// 신규 가입
	@Test
	void login_createsNewMemberWithGeneratedNickname_whenNotRegistered() {
		given(appleTokenVerifier.verify(IDENTITY_TOKEN)).willReturn(new AppleTokenClaims(SUB, "test@apple.com"));
		given(tokenHasher.hash(anyString())).willReturn("some-hash");
		given(tokenHasher.hash(SUB)).willReturn(HASHED_SUB);
		given(oAuthRepository.existsByProviderAndProviderId(OAuthProvider.APPLE, HASHED_SUB)).willReturn(false);
		given(oAuthRepository.findByProviderAndProviderId(OAuthProvider.APPLE, HASHED_SUB)).willReturn(
			Optional.empty());
		given(nicknameGenerator.generate()).willReturn(GENERATED_NICKNAME);

		Member savedMember = memberOf(1L);
		given(savedMember.isDeleted()).willReturn(false);
		given(memberRepository.save(any(Member.class))).willReturn(savedMember);
		given(memberRepository.getReferenceById(1L)).willReturn(savedMember);

		OAuth savedOAuth = oauthWith(savedMember);
		given(oAuthRepository.save(any(OAuth.class))).willReturn(savedOAuth);
		given(notificationSettingRepository.findByMemberId(1L)).willReturn(
			Optional.of(mock(NotificationSetting.class)));
		given(jwtProvider.generateAccessToken(1L)).willReturn("access-token");
		given(jwtProvider.generateRefreshToken(1L)).willReturn("refresh-token");
		given(refreshTokenRepository.findByMemberAndDeviceId(any(), any())).willReturn(Optional.empty());

		AppleLoginResponse response = appleAuthService.login(IDENTITY_TOKEN, DEVICE_ID);

		assertThat(response.accessToken()).isEqualTo("access-token");
		assertThat(response.isNew()).isTrue();
		then(nicknameGenerator).should().generate();
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
		given(memberRepository.getReferenceById(2L)).willReturn(existingMember);
		given(notificationSettingRepository.findByMemberId(2L)).willReturn(
			Optional.of(mock(NotificationSetting.class)));
		given(jwtProvider.generateAccessToken(2L)).willReturn("access-token");
		given(jwtProvider.generateRefreshToken(2L)).willReturn("refresh-token");
		given(refreshTokenRepository.findByMemberAndDeviceId(any(), any())).willReturn(Optional.empty());

		AppleLoginResponse response = appleAuthService.login(IDENTITY_TOKEN, DEVICE_ID);

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

		Member newMember = memberOf(3L);
		given(memberRepository.save(any(Member.class))).willReturn(newMember);
		given(memberRepository.getReferenceById(3L)).willReturn(newMember);
		given(notificationSettingRepository.findByMemberId(3L)).willReturn(
			Optional.of(mock(NotificationSetting.class)));
		given(jwtProvider.generateAccessToken(3L)).willReturn("access-token");
		given(jwtProvider.generateRefreshToken(3L)).willReturn("refresh-token");
		given(refreshTokenRepository.findByMemberAndDeviceId(any(), any())).willReturn(Optional.empty());

		AppleLoginResponse response = appleAuthService.login(IDENTITY_TOKEN, DEVICE_ID);

		assertThat(response.isNew()).isTrue();
		then(nicknameGenerator).should().generate();
		then(existingOAuth).should().updateMember(newMember);
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
