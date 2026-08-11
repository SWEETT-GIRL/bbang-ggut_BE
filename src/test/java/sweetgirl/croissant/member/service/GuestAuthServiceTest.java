package sweetgirl.croissant.member.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.global.encrypt.TokenHasher;
import sweetgirl.croissant.member.dto.response.GuestLoginResponse;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.entity.RefreshToken;
import sweetgirl.croissant.member.jwt.JwtProvider;
import sweetgirl.croissant.member.repository.MemberRepository;
import sweetgirl.croissant.member.repository.NotificationSettingRepository;
import sweetgirl.croissant.member.repository.RefreshTokenRepository;
import sweetgirl.croissant.member.util.NicknameGenerator;

@ExtendWith(MockitoExtension.class)
class GuestAuthServiceTest {

	private static final String DEVICE_ID = "device-001";
	private static final String NICKNAME = "신난 두쫀쿠";

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
	private GuestAuthService guestAuthService;

	// 같은 기기의 동시 요청이 각자 게스트를 만들지 않으려면, 조회를 시작하기 전에 기기를 잠가야 한다.
	@Test
	@DisplayName("기존 게스트를 찾기 전에 기기 락을 먼저 잡는다")
	void login_locksDevice_beforeLookingUpExistingGuest() {
		givenExistingGuest(1L);

		guestAuthService.login(DEVICE_ID);

		InOrder inOrder = inOrder(refreshTokenRepository);
		inOrder.verify(refreshTokenRepository).lockGuestDevice(DEVICE_ID.hashCode());
		inOrder.verify(refreshTokenRepository).findGuestTokensByDeviceId(DEVICE_ID);
	}

	@Test
	@DisplayName("deviceId가 비면 잠글 기기가 없으니 락을 잡지 않는다")
	void login_skipsLock_whenDeviceIdIsBlank() {
		Member created = memberOf(2L);
		given(memberRepository.save(any(Member.class))).willReturn(created);
		given(nicknameGenerator.generate()).willReturn(NICKNAME);
		given(memberRepository.getReferenceById(2L)).willReturn(created);

		guestAuthService.login("");

		then(refreshTokenRepository).should(never()).lockGuestDevice(anyLong());
	}

	@Test
	@DisplayName("같은 기기에 게스트가 이미 있으면 새로 만들지 않고 재사용한다")
	void login_reusesGuest_whenDeviceAlreadyHasOne() {
		givenExistingGuest(1L);
		given(jwtProvider.generateAccessToken(1L)).willReturn("access-token");

		GuestLoginResponse response = guestAuthService.login(DEVICE_ID);

		assertThat(response.memberId()).isEqualTo(1L);
		assertThat(response.accessToken()).isEqualTo("access-token");
		then(memberRepository).should(never()).save(any(Member.class));
	}

	@Test
	@DisplayName("기기에 게스트가 없으면 새로 만든다")
	void login_createsGuest_whenDeviceIsUnknown() {
		given(refreshTokenRepository.findGuestTokensByDeviceId(DEVICE_ID)).willReturn(List.of());
		Member created = memberOf(2L);
		given(memberRepository.save(any(Member.class))).willReturn(created);
		given(memberRepository.getReferenceById(2L)).willReturn(created);
		given(nicknameGenerator.generate()).willReturn(NICKNAME);

		GuestLoginResponse response = guestAuthService.login(DEVICE_ID);

		assertThat(response.memberId()).isEqualTo(2L);
		then(memberRepository).should().save(any(Member.class));
	}

	// 새 토큰을 저장하기 전 기존 토큰 정리는 벌크 삭제여야 한다.
	// 엔티티를 읽어 delete하면 동시 요청이 먼저 지운 뒤 flush될 때 StaleStateException으로 500이 났다.
	@Test
	@DisplayName("기존 토큰은 벌크 삭제로 정리한다")
	void login_clearsPreviousTokenWithBulkDelete() {
		givenExistingGuest(1L);

		guestAuthService.login(DEVICE_ID);

		then(refreshTokenRepository).should().deleteByMemberIdAndDeviceId(1L, DEVICE_ID);
		then(refreshTokenRepository).should(never()).delete(any(RefreshToken.class));
	}

	private void givenExistingGuest(Long memberId) {
		Member member = memberOf(memberId);
		RefreshToken token = mock(RefreshToken.class);
		given(token.getMember()).willReturn(member);
		given(refreshTokenRepository.findGuestTokensByDeviceId(DEVICE_ID)).willReturn(List.of(token));
		given(memberRepository.getReferenceById(memberId)).willReturn(member);
	}

	private Member memberOf(Long id) {
		Member member = mock(Member.class);
		given(member.getId()).willReturn(id);
		return member;
	}
}
