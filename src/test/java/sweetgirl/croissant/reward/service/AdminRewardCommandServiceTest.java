package sweetgirl.croissant.reward.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.member.code.MemberErrorCode;
import sweetgirl.croissant.member.exception.MemberException;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.reward.code.RewardErrorCode;
import sweetgirl.croissant.reward.dto.request.AdminCrumbAdjustRequest;
import sweetgirl.croissant.reward.dto.request.AdminRewardSettingsUpdateRequest;
import sweetgirl.croissant.reward.dto.response.AdminCrumbAdjustResponse;
import sweetgirl.croissant.reward.entity.RewardSetting;
import sweetgirl.croissant.reward.enums.RewardSettingKey;
import sweetgirl.croissant.reward.exception.RewardException;
import sweetgirl.croissant.reward.repository.RewardSettingRepository;
import sweetgirl.croissant.reward.service.GrantResult.Outcome;

@ExtendWith(MockitoExtension.class)
class AdminRewardCommandServiceTest {

	private static final Long MEMBER_ID = 7L;

	@Mock
	private CrumbGrantService crumbGrantService;

	@Mock
	private RewardSettingRepository rewardSettingRepository;

	@Mock
	private RewardSettings rewardSettings;

	@Mock
	private MemberReader memberReader;

	@InjectMocks
	private AdminRewardCommandService adminRewardCommandService;

	@Nested
	@DisplayName("지급·회수")
	class Adjust {

		@Test
		@DisplayName("지급하면 반영된 양과 잔액을 돌려준다")
		void grant() {
			UUID requestId = UUID.randomUUID();
			given(crumbGrantService.grantByAdmin(7L, 100, "admin:" + requestId, "광고 오류 보상"))
				.willReturn(new GrantResult(Outcome.GRANTED, 100, 20, 120));

			AdminCrumbAdjustResponse response = adminRewardCommandService.grant(
				MEMBER_ID, new AdminCrumbAdjustRequest(100, "광고 오류 보상", requestId));

			assertThat(response).isEqualTo(new AdminCrumbAdjustResponse(true, 100, 120));
		}

		@Test
		@DisplayName("같은 requestId 가 다시 오면 반영되지 않았다고 답한다")
		void grantReplayed() {
			given(crumbGrantService.grantByAdmin(eq(7L), eq(100), anyString(), anyString()))
				.willReturn(new GrantResult(Outcome.ALREADY_GRANTED, 0, 120, 120));

			AdminCrumbAdjustResponse response = adminRewardCommandService.grant(
				MEMBER_ID, new AdminCrumbAdjustRequest(100, "광고 오류 보상", UUID.randomUUID()));

			assertThat(response).isEqualTo(new AdminCrumbAdjustResponse(false, 0, 120));
		}

		@Test
		@DisplayName("회수는 실제로 뺀 양을 돌려준다")
		void revoke() {
			given(crumbGrantService.revokeByAdmin(eq(7L), eq(100), anyString(), eq("오지급 회수")))
				.willReturn(new SpendResult(true, 40, 40, 0));

			AdminCrumbAdjustResponse response = adminRewardCommandService.revoke(
				MEMBER_ID, new AdminCrumbAdjustRequest(100, "오지급 회수", null));

			assertThat(response).isEqualTo(new AdminCrumbAdjustResponse(true, 40, 0));
		}

		@Test
		@DisplayName("게스트에게는 지급하지 않는다")
		void guestBlocked() {
			willThrow(new MemberException(MemberErrorCode.GUEST_LOGIN_REQUIRED))
				.given(memberReader).requireLoggedIn(MEMBER_ID);

			assertThatThrownBy(() -> adminRewardCommandService.grant(
				MEMBER_ID, new AdminCrumbAdjustRequest(100, "보상", null)))
				.isInstanceOf(MemberException.class);
			then(crumbGrantService).shouldHaveNoInteractions();
		}
	}

	@Nested
	@DisplayName("보상 수치 변경")
	class UpdateSettings {

		@Test
		@DisplayName("있는 키는 값을 바꾸고 캐시를 비운다")
		void changesExisting() {
			RewardSetting record = RewardSetting.of("record", 10, LocalDateTime.now().minusDays(1));
			given(rewardSettingRepository.findById("record")).willReturn(Optional.of(record));

			adminRewardCommandService.updateSettings(new AdminRewardSettingsUpdateRequest(
				List.of(new AdminRewardSettingsUpdateRequest.Entry("record", 12))));

			assertThat(record.getIntValue()).isEqualTo(12);
			then(rewardSettings).should().evict();
		}

		@Test
		@DisplayName("표에 행이 없던 키는 새로 넣는다")
		void insertsMissingRow() {
			given(rewardSettingRepository.findById("letter")).willReturn(Optional.empty());

			adminRewardCommandService.updateSettings(new AdminRewardSettingsUpdateRequest(
				List.of(new AdminRewardSettingsUpdateRequest.Entry("letter", 15))));

			then(rewardSettingRepository).should().save(any(RewardSetting.class));
		}

		@Test
		@DisplayName("모르는 키가 섞여 있으면 REWARD404_1 이고 아무것도 바꾸지 않는다")
		void unknownKey() {
			assertThatThrownBy(() -> adminRewardCommandService.updateSettings(new AdminRewardSettingsUpdateRequest(
				List.of(new AdminRewardSettingsUpdateRequest.Entry("record", 12),
					new AdminRewardSettingsUpdateRequest.Entry("no-such-key", 1)))))
				.isInstanceOf(RewardException.class)
				.extracting("errorCode").isEqualTo(RewardErrorCode.REWARD_SETTING_NOT_FOUND);
			then(rewardSettingRepository).should(never()).findById(anyString());
			then(rewardSettings).should(never()).evict();
		}

		@Test
		@DisplayName("출시 시각이 없는 날짜면 REWARD400_9 이고 아무것도 바꾸지 않는다")
		void invalidCarryOverBefore() {
			assertThatThrownBy(() -> adminRewardCommandService.updateSettings(new AdminRewardSettingsUpdateRequest(
				List.of(new AdminRewardSettingsUpdateRequest.Entry("carry-over.before-date", 20261341)))))
				.isInstanceOf(RewardException.class)
				.extracting("errorCode").isEqualTo(RewardErrorCode.REWARD_SETTING_VALUE_INVALID);
			then(rewardSettingRepository).should(never()).findById(anyString());
		}

		@Test
		@DisplayName("출시 시각은 이미 있는 날짜에 시각만 따로 바꿔도 함께 본다")
		void invalidTimeAgainstStoredDate() {
			given(rewardSettings.get(RewardSettingKey.CARRY_OVER_BEFORE_DATE)).willReturn(20261201);

			assertThatThrownBy(() -> adminRewardCommandService.updateSettings(new AdminRewardSettingsUpdateRequest(
				List.of(new AdminRewardSettingsUpdateRequest.Entry("carry-over.before-time", 2460)))))
				.isInstanceOf(RewardException.class)
				.extracting("errorCode").isEqualTo(RewardErrorCode.REWARD_SETTING_VALUE_INVALID);
		}

		@Test
		@DisplayName("출시 날짜와 시각을 함께 넣는다")
		void setsCarryOverBefore() {
			adminRewardCommandService.updateSettings(new AdminRewardSettingsUpdateRequest(
				List.of(new AdminRewardSettingsUpdateRequest.Entry("carry-over.before-date", 20261201),
					new AdminRewardSettingsUpdateRequest.Entry("carry-over.before-time", 1430))));

			then(rewardSettingRepository).should(times(2)).save(any(RewardSetting.class));
		}
	}
}
