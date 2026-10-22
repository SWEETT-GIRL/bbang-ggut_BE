package sweetgirl.croissant.dev.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.alarm.service.NotificationSettingProvider;
import sweetgirl.croissant.dev.dto.response.DevTestLoginResponse;
import sweetgirl.croissant.global.encrypt.TokenHasher;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.entity.RefreshToken;
import sweetgirl.croissant.member.jwt.JwtProvider;
import sweetgirl.croissant.member.repository.MemberRepository;
import sweetgirl.croissant.member.repository.RefreshTokenRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("DevAuthService")
class DevAuthServiceTest {

	@Mock
	private MemberRepository memberRepository;
	@Mock
	private RefreshTokenRepository refreshTokenRepository;
	@Mock
	private NotificationSettingProvider notificationSettingProvider;
	@Mock
	private JwtProvider jwtProvider;
	@Mock
	private TokenHasher tokenHasher;

	@InjectMocks
	private DevAuthService devAuthService;

	@BeforeEach
	void setUp() {
		ReflectionTestUtils.setField(devAuthService, "refreshTokenExpiry", 3_600_000L);
		given(jwtProvider.generateAccessToken(any())).willReturn("access");
		given(jwtProvider.generateRefreshToken(any())).willReturn("refresh");
		given(tokenHasher.hash("refresh")).willReturn("hashed");
	}

	private Member member(Long id, String nickname) {
		Member member = Member.builder().nickname(nickname).build();
		ReflectionTestUtils.setField(member, "id", id);
		return member;
	}

	@Test
	@DisplayName("닉네임이 없으면 USER 회원을 만들고 토큰을 준다")
	void createsWhenMissing() {
		given(memberRepository.findByNicknameAndDeletedAtIsNull("테스트빵순이")).willReturn(Optional.empty());
		given(memberRepository.save(any())).willAnswer(invocation -> {
			Member saved = invocation.getArgument(0);
			ReflectionTestUtils.setField(saved, "id", 10L);
			return saved;
		});

		DevTestLoginResponse response = devAuthService.login("테스트빵순이", null);

		assertThat(response.created()).isTrue();
		assertThat(response.memberId()).isEqualTo(10L);
		assertThat(response.accessToken()).isEqualTo("access");
		then(notificationSettingProvider).should().getOrCreate(10L);
		ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
		then(refreshTokenRepository).should().save(captor.capture());
		assertThat(captor.getValue().getDeviceId()).isEqualTo("dev-test-테스트빵순이");
		assertThat(captor.getValue().getToken()).isEqualTo("hashed");
	}

	@Test
	@DisplayName("닉네임이 있으면 그 회원으로 다시 로그인한다(새로 만들지 않음)")
	void reusesExisting() {
		given(memberRepository.findByNicknameAndDeletedAtIsNull("테스트빵순이"))
			.willReturn(Optional.of(member(10L, "테스트빵순이")));

		DevTestLoginResponse response = devAuthService.login("테스트빵순이", "iphone");

		assertThat(response.created()).isFalse();
		assertThat(response.memberId()).isEqualTo(10L);
		then(memberRepository).should(never()).save(any());
		then(refreshTokenRepository).should().deleteByMemberIdAndDeviceId(10L, "iphone");
	}
}
