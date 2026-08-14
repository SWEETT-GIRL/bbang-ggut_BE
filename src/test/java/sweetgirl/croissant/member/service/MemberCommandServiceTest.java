package sweetgirl.croissant.member.service;

import static org.mockito.BDDMockito.*;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.alarm.repository.FcmTokenRepository;
import sweetgirl.croissant.member.dto.request.ConsentCreateRequest;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.entity.RefreshToken;
import sweetgirl.croissant.member.repository.LikedMenuCategoryRepository;
import sweetgirl.croissant.member.repository.MemberRepository;
import sweetgirl.croissant.member.repository.NotificationSettingRepository;
import sweetgirl.croissant.member.repository.RefreshTokenRepository;
import sweetgirl.croissant.menu.repository.MenuCategoryRepository;

@ExtendWith(MockitoExtension.class)
class MemberCommandServiceTest {

	@Mock
	private MemberRepository memberRepository;
	@Mock
	private MenuCategoryRepository menuCategoryRepository;
	@Mock
	private LikedMenuCategoryRepository likedMenuCategoryRepository;
	@Mock
	private NotificationSettingRepository notificationSettingRepository;
	@Mock
	private MemberReader memberReader;
	@Mock
	private RefreshTokenRepository refreshTokenRepository;
	@Mock
	private FcmTokenRepository fcmTokenRepository;
	@Mock
	private GuestPromotionService guestPromotionService;

	@InjectMocks
	private MemberCommandService memberCommandService;

	// 최초 동의 저장 = 가입 확정 — 로그인 때 미뤄둔 게스트 이관을 세션의 deviceId로 실행한다
	@Test
	void createConsent_runsDeferredGuestMerge_perSessionDeviceId() {
		Member member = mock(Member.class);
		given(memberReader.getMember(1L)).willReturn(member);
		RefreshToken session = mock(RefreshToken.class);
		given(session.getDeviceId()).willReturn("device-001");
		given(refreshTokenRepository.findAllByMember(member)).willReturn(List.of(session));

		memberCommandService.createConsent(1L,
			new ConsentCreateRequest(true, true, true, false, false, true, "v1", "v1", null));

		then(member).should()
			.applyInitialConsent(true, true, true, false, false, true, "v1", "v1");
		then(guestPromotionService).should().mergeGuestDataInto(member, "device-001", null);
	}

	// 세션이 없으면(비정상 경로) 이관 없이 동의만 저장된다
	@Test
	void createConsent_skipsGuestMerge_whenNoSession() {
		Member member = mock(Member.class);
		given(memberReader.getMember(1L)).willReturn(member);
		given(refreshTokenRepository.findAllByMember(member)).willReturn(List.of());

		memberCommandService.createConsent(1L,
			new ConsentCreateRequest(true, true, true, false, false, true, "v1", "v1", null));

		then(guestPromotionService).shouldHaveNoInteractions();
	}
}
