package sweetgirl.croissant.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.member.dto.response.ConsentResponse;
import sweetgirl.croissant.member.dto.response.DayOfWeekResponse;
import sweetgirl.croissant.member.dto.response.MemberProfileResponse;
import sweetgirl.croissant.member.dto.response.MenuCategoryResponse;
import sweetgirl.croissant.member.dto.response.NotificationRadiusResponse;
import sweetgirl.croissant.member.dto.response.NotificationTimeResponse;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.entity.NotificationSetting;
import sweetgirl.croissant.member.enums.NotificationRadius;
import sweetgirl.croissant.member.exception.MemberException;
import sweetgirl.croissant.member.repository.LikedMenuCategoryRepository;
import sweetgirl.croissant.member.repository.NotificationSettingRepository;
import sweetgirl.croissant.menu.entity.MenuCategory;
import sweetgirl.croissant.menu.enums.MenuKind;
import sweetgirl.croissant.store.repository.SubscribedStoreRepository;

@ExtendWith(MockitoExtension.class)
class MemberQueryServiceTest {

	@Mock
	SubscribedStoreRepository subscribedStoreRepository;

	@Mock
	LikedMenuCategoryRepository likedMenuCategoryRepository;

	@Mock
	NotificationSettingRepository notificationSettingRepository;

	@Mock
	MemberReader memberReader;

	@Mock
	MemberRestrictionReader memberRestrictionReader;

	@InjectMocks
	MemberQueryService memberQueryService;

	private static final Long MEMBER_ID = 1L;

	@Test
	@DisplayName("getProfile: 회원 프로필을 반환한다")
	void getProfile_returnsProfile() {
		Member member = mock(Member.class);
		given(member.getId()).willReturn(MEMBER_ID);
		given(member.getNickname()).willReturn("빵돌이");
		given(memberReader.getMember(MEMBER_ID)).willReturn(member);
		given(memberRestrictionReader.currentSuspension(MEMBER_ID)).willReturn(Optional.empty());

		MemberProfileResponse result = memberQueryService.getProfile(MEMBER_ID);

		assertThat(result.memberId()).isEqualTo(MEMBER_ID);
		assertThat(result.nickname()).isEqualTo("빵돌이");
		assertThat(result.suspended()).isFalse();
		assertThat(result.suspendedUntil()).isNull();
	}

	@Test
	@DisplayName("getProfile: 정지 중이면 suspended 와 풀리는 때가 실린다 — 마이 위 정지 배너")
	void getProfile_suspended() {
		Member member = mock(Member.class);
		given(member.getId()).willReturn(MEMBER_ID);
		given(memberReader.getMember(MEMBER_ID)).willReturn(member);
		LocalDateTime until = LocalDateTime.of(2026, 11, 1, 0, 0);
		given(memberRestrictionReader.currentSuspension(MEMBER_ID))
			.willReturn(Optional.of(new MemberRestrictionReader.Suspension(until)));

		MemberProfileResponse result = memberQueryService.getProfile(MEMBER_ID);

		assertThat(result.suspended()).isTrue();
		assertThat(result.suspendedUntil()).isEqualTo(until);
	}

	@Test
	@DisplayName("getSubscribedStoreCount: 회원이 알림받는 빵집 수를 반환한다")
	void getSubscribedStoreCount_returnsCount() {
		Member member = mock(Member.class);
		given(memberReader.getMember(MEMBER_ID)).willReturn(member);
		given(subscribedStoreRepository.countByMember(member)).willReturn(5L);

		long result = memberQueryService.getSubscribedStoreCount(MEMBER_ID);

		assertThat(result).isEqualTo(5L);
	}

	@Test
	@DisplayName("getConsent: 회원 동의 정보를 반환한다")
	void getConsent_returnsConsent() {
		Member member = mock(Member.class);
		given(member.isAgreeToTerms()).willReturn(true);
		given(member.isAgreeToMarketing()).willReturn(false);
		given(memberReader.getMember(MEMBER_ID)).willReturn(member);

		ConsentResponse result = memberQueryService.getConsent(MEMBER_ID);

		assertThat(result.agreeToTerms()).isTrue();
		assertThat(result.agreeToMarketing()).isFalse();
	}

	@Test
	@DisplayName("getLikedMenu: 회원이 좋아하는 빵 종류 목록을 반환한다")
	void getLikedMenu_returnsBreadKinds() {
		MenuCategory saltBread = mock(MenuCategory.class);
		given(saltBread.getName()).willReturn(MenuKind.소금빵);
		MenuCategory bagel = mock(MenuCategory.class);
		given(bagel.getName()).willReturn(MenuKind.베이글);
		given(likedMenuCategoryRepository.findMenuCategoriesByMemberId(MEMBER_ID))
			.willReturn(List.of(saltBread, bagel));

		MenuCategoryResponse result = memberQueryService.getLikedMenu(MEMBER_ID);

		assertThat(result.getBreadKind()).containsExactly("소금빵", "베이글");
	}

	@Test
	@DisplayName("getNotificationDays: 저장된 요일 문자열을 배열로 분리해 반환한다")
	void getNotificationDays_splitsStoredDays() {
		NotificationSetting setting = mock(NotificationSetting.class);
		given(setting.getDaysOfWeek()).willReturn("MONDAY,TUESDAY,FRIDAY");
		given(notificationSettingRepository.findByMemberId(MEMBER_ID)).willReturn(Optional.of(setting));

		DayOfWeekResponse result = memberQueryService.getNotificationDays(MEMBER_ID);

		assertThat(result.getNotificationDays()).containsExactly("MONDAY", "TUESDAY", "FRIDAY");
	}

	@Test
	@DisplayName("getNotificationDays: 저장된 요일이 비어 있으면 빈 배열을 반환한다")
	void getNotificationDays_returnsEmpty_whenBlank() {
		NotificationSetting setting = mock(NotificationSetting.class);
		given(setting.getDaysOfWeek()).willReturn("");
		given(notificationSettingRepository.findByMemberId(MEMBER_ID)).willReturn(Optional.of(setting));

		DayOfWeekResponse result = memberQueryService.getNotificationDays(MEMBER_ID);

		assertThat(result.getNotificationDays()).isNull();
	}

	@Test
	@DisplayName("getNotificationDays: 알림 설정이 없으면 MemberException을 던진다")
	void getNotificationDays_throws_whenSettingAbsent() {
		given(notificationSettingRepository.findByMemberId(MEMBER_ID)).willReturn(Optional.empty());

		assertThatThrownBy(() -> memberQueryService.getNotificationDays(MEMBER_ID))
			.isInstanceOf(MemberException.class);
	}

	@Test
	@DisplayName("getNotificationRadius: 저장된 알림 반경을 반환한다")
	void getNotificationRadius_returnsRadius() {
		NotificationSetting setting = mock(NotificationSetting.class);
		given(setting.getNotificationRadius()).willReturn(NotificationRadius.R500);
		given(notificationSettingRepository.findByMemberId(MEMBER_ID)).willReturn(Optional.of(setting));

		NotificationRadiusResponse result = memberQueryService.getNotificationRadius(MEMBER_ID);

		assertThat(result.getRadius()).isEqualTo(NotificationRadius.R500.getValue());
	}

	@Test
	@DisplayName("getNotificationRadius: 알림 설정이 없으면 MemberException을 던진다")
	void getNotificationRadius_throws_whenSettingAbsent() {
		given(notificationSettingRepository.findByMemberId(MEMBER_ID)).willReturn(Optional.empty());

		assertThatThrownBy(() -> memberQueryService.getNotificationRadius(MEMBER_ID))
			.isInstanceOf(MemberException.class);
	}

	@Test
	@DisplayName("getNotificationTime: 저장된 알림 시작·종료 시간을 반환한다")
	void getNotificationTime_returnsTime() {
		NotificationSetting setting = mock(NotificationSetting.class);
		given(setting.getStartTime()).willReturn(LocalTime.of(8, 0));
		given(setting.getEndTime()).willReturn(LocalTime.of(21, 0));
		given(notificationSettingRepository.findByMemberId(MEMBER_ID)).willReturn(Optional.of(setting));

		NotificationTimeResponse result = memberQueryService.getNotificationTime(MEMBER_ID);

		assertThat(result.getStartTime()).isEqualTo("08:00");
		assertThat(result.getEndTime()).isEqualTo("21:00");
	}

	@Test
	@DisplayName("getPrivacy: 비공개 계정 여부를 돌려준다")
	void getPrivacy() {
		Member member = Member.builder().nickname("빵돌이").build();
		member.changePrivacy(true);
		given(memberReader.getMember(MEMBER_ID)).willReturn(member);

		assertThat(memberQueryService.getPrivacy(MEMBER_ID).isPrivate()).isTrue();
	}
}
