package sweetgirl.croissant.member.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import sweetgirl.croissant.global.encrypt.TokenHasher;
import sweetgirl.croissant.member.code.MemberErrorCode;
import sweetgirl.croissant.member.dto.response.KakaoLoginResponse;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.entity.NotificationSetting;
import sweetgirl.croissant.member.entity.OAuth;
import sweetgirl.croissant.member.enums.OAuthProvider;
import sweetgirl.croissant.member.exception.MemberException;
import sweetgirl.croissant.member.jwt.JwtProvider;
import sweetgirl.croissant.member.repository.MemberRepository;
import sweetgirl.croissant.member.repository.MemberRestrictionRepository;
import sweetgirl.croissant.member.repository.NotificationSettingRepository;
import sweetgirl.croissant.member.repository.OAuthRepository;
import sweetgirl.croissant.member.repository.RefreshTokenRepository;
import sweetgirl.croissant.member.util.NicknameGenerator;

@ExtendWith(MockitoExtension.class)
class KakaoAuthServiceTest {

	@Mock
	OAuthRepository oAuthRepository;
	@Mock
	MemberRepository memberRepository;
	@Mock
	GuestPromotionService guestPromotionService;
	@Mock
	MemberRestrictionRepository memberRestrictionRepository;
	@Mock
	RefreshTokenRepository refreshTokenRepository;
	@Mock
	NotificationSettingRepository notificationSettingRepository;
	@Mock
	JwtProvider jwtProvider;
	@Mock
	TokenHasher tokenHasher;
	@Mock
	NicknameGenerator nicknameGenerator;
	@Mock
	ApplicationEventPublisher eventPublisher;

	/** MockRestServiceServer 가 붙으려면 진짜 인스턴스여야 해서 목이 아니라 @Spy 다. */
	@Spy
	RestTemplate restTemplate = new RestTemplate();

	@InjectMocks
	KakaoAuthService kakaoAuthService;

	@Test
	void login_throwsUnauthorizedMemberException_whenKakaoRejectsToken() {
		MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
		server.expect(requestTo("https://kapi.kakao.com/v2/user/me"))
			.andRespond(withStatus(HttpStatus.UNAUTHORIZED));

		assertThatThrownBy(() -> kakaoAuthService.login("invalid-token", "device-1", null))
			.isInstanceOfSatisfying(MemberException.class,
				ex -> org.assertj.core.api.Assertions.assertThat(ex.getErrorCode())
					.isEqualTo(MemberErrorCode.SOCIAL_TOKEN_INVALID));
		server.verify();
	}

	// 탈퇴 후 재가입 — 새 회원을 만들고, 탈퇴한 옛 회원을 가리켜 둔다
	@Test
	void login_createsRejoinedMember_whenPreviouslyDeleted() {
		ReflectionTestUtils.setField(kakaoAuthService, "refreshTokenExpiry", 1_209_600_000L);
		MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
		server.expect(requestTo("https://kapi.kakao.com/v2/user/me"))
			.andRespond(withSuccess("{\"id\":12345}", MediaType.APPLICATION_JSON));
		given(tokenHasher.hash("12345")).willReturn("hashed-id");
		given(tokenHasher.hash("refresh-token")).willReturn("hashed-refresh");
		given(oAuthRepository.existsByProviderAndProviderId(OAuthProvider.KAKAO, "hashed-id")).willReturn(true);
		given(nicknameGenerator.generate()).willReturn("신난 두쫀쿠");

		Member deleted = mock(Member.class);
		given(deleted.getId()).willReturn(4L);
		given(deleted.isDeleted()).willReturn(true);
		OAuth oAuth = mock(OAuth.class);
		given(oAuth.getMember()).willReturn(deleted);
		given(oAuthRepository.findByProviderAndProviderId(OAuthProvider.KAKAO, "hashed-id"))
			.willReturn(Optional.of(oAuth));
		given(memberRestrictionRepository.findActiveByMemberIdAndRestrictionTypes(eq(4L), any(), any()))
			.willReturn(List.of());
		Member rejoinedMember = mock(Member.class);
		given(rejoinedMember.getId()).willReturn(5L);
		given(memberRepository.save(any(Member.class))).willReturn(rejoinedMember);
		given(notificationSettingRepository.findByMemberId(5L))
			.willReturn(Optional.of(mock(NotificationSetting.class)));
		given(jwtProvider.generateAccessToken(5L)).willReturn("access-token");
		given(jwtProvider.generateRefreshToken(5L)).willReturn("refresh-token");

		KakaoLoginResponse response = kakaoAuthService.login("kakao-token", "device-1", null);

		org.assertj.core.api.Assertions.assertThat(response.isNew()).isTrue();
		ArgumentCaptor<Member> rejoined = ArgumentCaptor.forClass(Member.class);
		then(memberRepository).should().save(rejoined.capture());
		org.assertj.core.api.Assertions.assertThat(rejoined.getValue().getPreviousMemberId()).isEqualTo(4L);
		org.assertj.core.api.Assertions.assertThat(rejoined.getValue().getRegisteredAt()).isNotNull();
		then(oAuth).should().updateMember(rejoinedMember);
	}
}
