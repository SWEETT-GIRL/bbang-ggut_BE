package sweetgirl.croissant.reward.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.EnumMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import sweetgirl.croissant.global.advice.GlobalExceptionHandler;
import sweetgirl.croissant.reward.code.RewardErrorCode;
import sweetgirl.croissant.reward.dto.response.AdminCrumbAdjustResponse;
import sweetgirl.croissant.reward.enums.RewardSettingKey;
import sweetgirl.croissant.reward.exception.RewardException;
import sweetgirl.croissant.reward.service.AdminRewardCommandService;
import sweetgirl.croissant.reward.service.RewardSettings;

@ExtendWith(MockitoExtension.class)
class AdminRewardControllerTest {

	private MockMvc mockMvc;

	@Mock
	private AdminRewardCommandService adminRewardCommandService;

	@Mock
	private RewardSettings rewardSettings;

	@InjectMocks
	private AdminRewardController adminRewardController;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(adminRewardController)
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	@Test
	@DisplayName("지급하면 반영된 양과 잔액을 내린다")
	void grant() throws Exception {
		given(adminRewardCommandService.grant(eq(7L), any()))
			.willReturn(new AdminCrumbAdjustResponse(true, 100, 120));

		mockMvc.perform(post("/api/admin/rewards/members/7/grant")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"amount\":100,\"memo\":\"광고 오류 보상\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.applied").value(true))
			.andExpect(jsonPath("$.data.amount").value(100))
			.andExpect(jsonPath("$.data.crumbs").value(120));
	}

	@Test
	@DisplayName("사유 없이 지급하려 하면 400 이고 서비스를 부르지 않는다")
	void grantWithoutMemo() throws Exception {
		mockMvc.perform(post("/api/admin/rewards/members/7/grant")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"amount\":100}"))
			.andExpect(status().isBadRequest());

		then(adminRewardCommandService).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("0 이하를 회수하려 하면 400")
	void revokeInvalidAmount() throws Exception {
		mockMvc.perform(post("/api/admin/rewards/members/7/revoke")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"amount\":0,\"memo\":\"회수\"}"))
			.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("보상 수치를 지금 값과 기본값으로 내린다")
	void getSettings() throws Exception {
		Map<RewardSettingKey, Integer> values = new EnumMap<>(RewardSettingKey.class);
		values.put(RewardSettingKey.RECORD, 12);
		given(rewardSettings.all()).willReturn(values);

		mockMvc.perform(get("/api/admin/rewards/settings"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data[0].key").value("record"))
			.andExpect(jsonPath("$.data[0].value").value(12))
			.andExpect(jsonPath("$.data[0].defaultValue").value(10));
	}

	@Test
	@DisplayName("모르는 키를 바꾸려 하면 REWARD404_1")
	void updateUnknownSetting() throws Exception {
		willThrow(new RewardException(RewardErrorCode.REWARD_SETTING_NOT_FOUND))
			.given(adminRewardCommandService).updateSettings(any());

		mockMvc.perform(put("/api/admin/rewards/settings")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"settings\":[{\"key\":\"no-such-key\",\"value\":1}]}"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("REWARD404_1"));
	}
}
