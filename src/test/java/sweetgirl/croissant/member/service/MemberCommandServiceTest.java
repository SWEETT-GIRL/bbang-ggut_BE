package sweetgirl.croissant.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.alarm.repository.FcmTokenRepository;
import sweetgirl.croissant.alarm.service.NotificationSettingProvider;
import sweetgirl.croissant.course.service.CourseMemberService;
import sweetgirl.croissant.member.code.MemberErrorCode;
import sweetgirl.croissant.member.dto.request.ConsentCreateRequest;
import sweetgirl.croissant.member.dto.request.ConsentUpdateRequest;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.entity.NotificationSetting;
import sweetgirl.croissant.member.entity.RefreshToken;
import sweetgirl.croissant.member.exception.MemberException;
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
	@Mock
	private CourseMemberService courseMemberService;
	@Mock
	private NotificationSettingProvider notificationSettingProvider;

	@InjectMocks
	private MemberCommandService memberCommandService;

	// 탈퇴 = soft delete + 세션 무효화 + 푸시 토큰 삭제 + 같이 편집하던 코스 정리(승계·나가기·메모 삭제)
	@Test
	void deleteMember_softDeletes_andLeavesCollabCourses() {
		Member member = mock(Member.class);
		given(memberRepository.findById(1L)).willReturn(Optional.of(member));
		RefreshToken session = mock(RefreshToken.class);
		given(refreshTokenRepository.findAllByMember(member)).willReturn(List.of(session));

		memberCommandService.deleteMember(1L);

		then(member).should().softDelete();
		then(courseMemberService).should().leaveAllCourses(1L);
		then(session).should().revoke();
		then(fcmTokenRepository).should().deleteAllByMember(member);
	}

	// 이미 탈퇴한 회원은 MEMBER_ALREADY_DELETED 이고 코스는 건드리지 않는다
	@Test
	void deleteMember_alreadyDeleted_throws() {
		Member member = mock(Member.class);
		given(member.getDeletedAt()).willReturn(LocalDateTime.of(2026, 9, 1, 0, 0));
		given(memberRepository.findById(1L)).willReturn(Optional.of(member));

		assertThatThrownBy(() -> memberCommandService.deleteMember(1L))
			.isInstanceOf(MemberException.class)
			.hasMessageContaining(MemberErrorCode.MEMBER_ALREADY_DELETED.getMessage());
		then(courseMemberService).shouldHaveNoInteractions();
		then(member).should(never()).softDelete();
	}

	// 최초 동의 저장 = 가입 확정 — 로그인 때 미뤄둔 게스트 이관을 세션의 deviceId로 실행한다
	@Test
	void createConsent_runsDeferredGuestMerge_perSessionDeviceId() {
		Member member = mock(Member.class);
		given(memberReader.getMember(1L)).willReturn(member);
		RefreshToken session = mock(RefreshToken.class);
		given(session.getDeviceId()).willReturn("device-001");
		given(refreshTokenRepository.findAllByMember(member)).willReturn(List.of(session));
		given(notificationSettingProvider.getOrCreate(1L)).willReturn(mock(NotificationSetting.class));

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
		given(notificationSettingProvider.getOrCreate(1L)).willReturn(mock(NotificationSetting.class));

		memberCommandService.createConsent(1L,
			new ConsentCreateRequest(true, true, true, false, false, true, "v1", "v1", null));

		then(guestPromotionService).shouldHaveNoInteractions();
	}

	// 약관에서 혜택 알림에 동의하면 발송이 보는 알림 설정(isMarketingEnabled)도 켜진다
	@Test
	void createConsent_marketingAgreed_turnsOnMarketingSetting() {
		Member member = Member.builder().nickname("테스터").build();
		given(memberReader.getMember(1L)).willReturn(member);
		given(refreshTokenRepository.findAllByMember(member)).willReturn(List.of());
		NotificationSetting setting = NotificationSetting.builder().member(member).isMarketingEnabled(false).build();
		given(notificationSettingProvider.getOrCreate(1L)).willReturn(setting);

		memberCommandService.createConsent(1L,
			new ConsentCreateRequest(true, true, true, true, false, true, "v1", "v1", null));

		assertThat(member.isAgreeToMarketing()).isTrue();
		assertThat(member.getMarketingAgreedAt()).isNotNull();
		assertThat(setting.isMarketingEnabled()).isTrue();
	}

	// 동의하지 않으면 설정도 꺼진 채다
	@Test
	void createConsent_marketingDeclined_keepsMarketingSettingOff() {
		Member member = Member.builder().nickname("테스터").build();
		given(memberReader.getMember(1L)).willReturn(member);
		given(refreshTokenRepository.findAllByMember(member)).willReturn(List.of());
		NotificationSetting setting = NotificationSetting.builder().member(member).isMarketingEnabled(true).build();
		given(notificationSettingProvider.getOrCreate(1L)).willReturn(setting);

		memberCommandService.createConsent(1L,
			new ConsentCreateRequest(true, true, true, false, false, true, "v1", "v1", null));

		assertThat(setting.isMarketingEnabled()).isFalse();
	}

	// 동의 변경 API로 마케팅 동의를 켜고 끄면 알림 설정도 따라간다
	@Test
	void updateConsent_marketing_syncsMarketingSetting() {
		Member member = Member.builder().nickname("테스터").build();
		given(memberReader.getMember(1L)).willReturn(member);
		NotificationSetting setting = NotificationSetting.builder().member(member).isMarketingEnabled(false).build();
		given(notificationSettingProvider.getOrCreate(1L)).willReturn(setting);

		memberCommandService.updateConsent(1L, new ConsentUpdateRequest(true, null));
		assertThat(setting.isMarketingEnabled()).isTrue();

		memberCommandService.updateConsent(1L, new ConsentUpdateRequest(false, null));
		assertThat(setting.isMarketingEnabled()).isFalse();
		assertThat(member.getMarketingAgreedAt()).isNull();
	}

	// 야간 동의만 바꾸는 요청은 알림 설정을 건드리지 않는다(설정에 야간 항목이 없다)
	@Test
	void updateConsent_nightOnly_doesNotTouchSetting() {
		Member member = Member.builder().nickname("테스터").build();
		given(memberReader.getMember(1L)).willReturn(member);

		memberCommandService.updateConsent(1L, new ConsentUpdateRequest(null, true));

		assertThat(member.isAgreeToNightMarketing()).isTrue();
		then(notificationSettingProvider).shouldHaveNoInteractions();
	}
}
