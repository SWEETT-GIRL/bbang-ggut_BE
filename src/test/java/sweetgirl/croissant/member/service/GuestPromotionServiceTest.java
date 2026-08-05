package sweetgirl.croissant.member.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.entity.RefreshToken;
import sweetgirl.croissant.member.repository.MemberRepository;
import sweetgirl.croissant.member.repository.RefreshTokenRepository;
import sweetgirl.croissant.member.util.NicknameGenerator;

@ExtendWith(MockitoExtension.class)
class GuestPromotionServiceTest {

	@Mock
	private RefreshTokenRepository refreshTokenRepository;
	@Mock
	private MemberRepository memberRepository;
	@Mock
	private NicknameGenerator nicknameGenerator;
	@Mock
	private GuestDataMigrationService guestDataMigrationService;

	@InjectMocks
	private GuestPromotionService guestPromotionService;

	private static final String DEVICE_ID = "device-001";
	private static final String GENERATED_NICKNAME = "신난 두쫀쿠";

	// 같은 기기의 게스트가 있으면 그 계정을 승격 — memberId가 유지돼야 코스 소유권이 따라간다
	@Test
	void promotesExistingGuest_whenGuestSessionExistsForDevice() {
		Member guest = mock(Member.class);
		RefreshToken guestToken = mock(RefreshToken.class);
		given(guestToken.getMember()).willReturn(guest);
		given(refreshTokenRepository.findGuestTokensByDeviceId(DEVICE_ID)).willReturn(List.of(guestToken));

		Member resolved = guestPromotionService.resolveMemberForNewOAuth(DEVICE_ID, null);

		assertThat(resolved).isSameAs(guest);
		then(guest).should().promoteToUser();
		then(memberRepository).should(never()).save(any(Member.class));
	}

	// 게스트 이력이 없으면(= 게스트 세션을 안 쓰는 앱) 기존처럼 새 Member를 만든다
	@Test
	void createsNewMember_whenNoGuestSessionForDevice() {
		given(refreshTokenRepository.findGuestTokensByDeviceId(DEVICE_ID)).willReturn(List.of());
		given(nicknameGenerator.generate()).willReturn(GENERATED_NICKNAME);
		Member created = mock(Member.class);
		given(memberRepository.save(any(Member.class))).willReturn(created);

		Member resolved = guestPromotionService.resolveMemberForNewOAuth(DEVICE_ID, null);

		assertThat(resolved).isSameAs(created);
		then(nicknameGenerator).should().generate();
	}

	// deviceId가 없으면 게스트 조회 자체를 하지 않는다
	@Test
	void createsNewMember_whenDeviceIdIsBlank() {
		given(nicknameGenerator.generate()).willReturn(GENERATED_NICKNAME);
		Member created = mock(Member.class);
		given(memberRepository.save(any(Member.class))).willReturn(created);

		Member resolved = guestPromotionService.resolveMemberForNewOAuth("  ", null);

		assertThat(resolved).isSameAs(created);
		then(refreshTokenRepository).shouldHaveNoInteractions();
	}

	// 이미 회원인 사람이 게스트로 쓰다 로그인하면 승격이 불가능하다 — 데이터만 옮겨 붙여야 한다
	@Test
	void migratesGuestData_whenLoggedInMemberDiffersFromGuest() {
		Member guest = mock(Member.class);
		given(guest.getId()).willReturn(10L);
		RefreshToken guestToken = mock(RefreshToken.class);
		given(guestToken.getMember()).willReturn(guest);
		given(refreshTokenRepository.findGuestTokensByDeviceId(DEVICE_ID)).willReturn(List.of(guestToken));

		Member loggedIn = mock(Member.class);
		given(loggedIn.getId()).willReturn(20L);

		guestPromotionService.mergeGuestDataInto(loggedIn, DEVICE_ID, null);

		then(guestDataMigrationService).should().migrate(10L, 20L);
	}

	// 승격으로 게스트가 곧 로그인 계정이 된 경우엔 옮길 대상이 없다
	@Test
	void doesNotMigrate_whenGuestIsAlreadyTheLoggedInMember() {
		Member same = mock(Member.class);
		given(same.getId()).willReturn(10L);
		RefreshToken guestToken = mock(RefreshToken.class);
		given(guestToken.getMember()).willReturn(same);
		given(refreshTokenRepository.findGuestTokensByDeviceId(DEVICE_ID)).willReturn(List.of(guestToken));

		guestPromotionService.mergeGuestDataInto(same, DEVICE_ID, null);

		then(guestDataMigrationService).shouldHaveNoInteractions();
	}

	// 게스트 이력이 없으면(첫 설치에 바로 로그인) 조회만 하고 끝난다
	@Test
	void doesNotMigrate_whenNoGuestSessionForDevice() {
		given(refreshTokenRepository.findGuestTokensByDeviceId(DEVICE_ID)).willReturn(List.of());
		Member loggedIn = mock(Member.class);

		guestPromotionService.mergeGuestDataInto(loggedIn, DEVICE_ID, null);

		then(guestDataMigrationService).shouldHaveNoInteractions();
	}
}
