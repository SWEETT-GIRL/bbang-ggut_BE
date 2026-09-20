package sweetgirl.croissant.member.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import sweetgirl.croissant.global.encrypt.TokenHasher;
import sweetgirl.croissant.member.code.MemberErrorCode;
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
}
