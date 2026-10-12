package sweetgirl.croissant.reward.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import sweetgirl.croissant.global.contract.ResponseContractWebMvcTest;
import sweetgirl.croissant.reward.dto.response.RewardClaimResponse;
import sweetgirl.croissant.reward.dto.response.RewardHistoryResponse;
import sweetgirl.croissant.reward.dto.response.RewardItemResponse;
import sweetgirl.croissant.reward.dto.response.RewardRulesResponse;
import sweetgirl.croissant.reward.dto.response.RewardSummaryResponse;
import sweetgirl.croissant.reward.dto.response.RewardSummaryResponse.CarryOver;
import sweetgirl.croissant.reward.dto.response.RewardSummaryResponse.Welcome;
import sweetgirl.croissant.reward.enums.CrumbReason;
import sweetgirl.croissant.reward.enums.RewardItemType;
import sweetgirl.croissant.reward.service.GrantResult;
import sweetgirl.croissant.reward.service.GrantResult.Outcome;
import sweetgirl.croissant.reward.service.RewardCommandService;
import sweetgirl.croissant.reward.service.RewardQueryService;

/**
 * 앱이 읽는 리워드 응답의 모양을 고정한다. 홈이 진입할 때마다 요약을 읽고, 보상이 생기는 모든 응답이 rewards[] 한 칸의 모양을 함께 쓴다.
 */
@ContextConfiguration(classes = RewardController.class)
class RewardSummaryResponseContractTest extends ResponseContractWebMvcTest {

	private static final RewardRulesResponse RULES = new RewardRulesResponse(
		25, 10, 5, 3, List.of(5, 5, 10, 5, 10, 10, 30), 2, 3, 10, 20, 2, 100, 10, 3, 8, 30, 30, 600);

	@MockitoBean
	private RewardCommandService rewardCommandService;

	@MockitoBean
	private RewardQueryService rewardQueryService;

	@Test
	void summary_keepsFieldsTheAppReads() throws Exception {
		RewardItemResponse pending = RewardItemResponse.crumbs(
			new GrantResult(Outcome.GRANTED, 100, 185, 285), CrumbReason.INVITE_INVITER).withId(31L);
		given(rewardCommandService.carryOverIfNeeded(any())).willReturn(Optional.empty());
		given(rewardQueryService.getSummary(any(), any())).willReturn(new RewardSummaryResponse(
			285, 26, RULES, new CarryOver(true, 110, 12), new Welcome(false), List.of(pending)));

		mockMvc.perform(get("/members/me/rewards/summary"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.crumbs").isNumber())
			.andExpect(jsonPath("$.data.gainedToday").isNumber())
			.andExpect(jsonPath("$.data.rules.record").isNumber())
			.andExpect(jsonPath("$.data.rules.recordPhoto").isNumber())
			.andExpect(jsonPath("$.data.rules.recordDailyCap").isNumber())
			.andExpect(jsonPath("$.data.rules.attendance", hasSize(7)))
			.andExpect(jsonPath("$.data.rules.saveStore").isNumber())
			.andExpect(jsonPath("$.data.rules.saveStoreDailyCap").isNumber())
			.andExpect(jsonPath("$.data.rules.letter").isNumber())
			.andExpect(jsonPath("$.data.rules.ad").isNumber())
			.andExpect(jsonPath("$.data.rules.adDailyCap").isNumber())
			.andExpect(jsonPath("$.data.rules.invite").isNumber())
			.andExpect(jsonPath("$.data.rules.inviteCap").isNumber())
			.andExpect(jsonPath("$.data.rules.tourVisit").isNumber())
			.andExpect(jsonPath("$.data.rules.tourVisitCap").isNumber())
			.andExpect(jsonPath("$.data.rules.tourComplete").isNumber())
			.andExpect(jsonPath("$.data.rules.tourPhotoComplete").isNumber())
			.andExpect(jsonPath("$.data.rules.wakeFriend").isNumber())
			.andExpect(jsonPath("$.data.carryOver.granted").isBoolean())
			.andExpect(jsonPath("$.data.carryOver.crumbs").isNumber())
			.andExpect(jsonPath("$.data.carryOver.recordCount").isNumber())
			.andExpect(jsonPath("$.data.welcome.available").isBoolean())
			// rewards[] 한 칸 — 기록 저장·출석 등 뒤 phase 의 응답도 같은 모양을 쓴다
			.andExpect(jsonPath("$.data.pendingRewards[0].id").isNumber())
			.andExpect(jsonPath("$.data.pendingRewards[0].type").value("CRUMBS"))
			.andExpect(jsonPath("$.data.pendingRewards[0].amount").isNumber())
			.andExpect(jsonPath("$.data.pendingRewards[0].from").isNumber())
			.andExpect(jsonPath("$.data.pendingRewards[0].to").isNumber())
			.andExpect(jsonPath("$.data.pendingRewards[0].reason").value("INVITE_INVITER"))
			.andExpect(jsonPath("$.data.pendingRewards[0].capped").isBoolean());
	}

	@Test
	void summary_keepsCarryOverKeyEvenWhenNull() throws Exception {
		given(rewardCommandService.carryOverIfNeeded(any())).willReturn(Optional.empty());
		given(rewardQueryService.getSummary(any(), any())).willReturn(RewardSummaryResponse.guest(RULES));

		mockMvc.perform(get("/members/me/rewards/summary"))
			.andExpect(status().isOk())
			// 이관이 없던 조회는 null 로 내려간다 — 앱은 null 이 아닐 때만 안내 화면을 띄운다
			.andExpect(jsonPath("$.data.carryOver").value((Object)null))
			.andExpect(jsonPath("$.data.pendingRewards").isEmpty());
	}

	@Test
	void levelUpItem_omitsFieldsItDoesNotUse() throws Exception {
		given(rewardCommandService.carryOverIfNeeded(any())).willReturn(Optional.empty());
		given(rewardQueryService.getSummary(any(), any())).willReturn(new RewardSummaryResponse(
			0, 0, RULES, null, new Welcome(false), List.of(new RewardItemResponse(
				32L, RewardItemType.LEVEL_UP, null, 2, 3, null, null, "earth", "space", null, null))));

		mockMvc.perform(get("/members/me/rewards/summary"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.pendingRewards[0].type").value("LEVEL_UP"))
			.andExpect(jsonPath("$.data.pendingRewards[0].planet").value("earth"))
			.andExpect(jsonPath("$.data.pendingRewards[0].stage").value("space"))
			.andExpect(jsonPath("$.data.pendingRewards[0].amount").doesNotExist())
			.andExpect(jsonPath("$.data.pendingRewards[0].capped").doesNotExist());
	}

	@Test
	void history_keepsFieldsTheAppReads() throws Exception {
		given(rewardQueryService.getHistory(any(), any(), any(Integer.class))).willReturn(new RewardHistoryResponse(
			List.of(new RewardHistoryResponse.Item(
				91L, 10, CrumbReason.RECORD, 285, LocalDateTime.of(2026, 10, 12, 9, 30))),
			91L));

		mockMvc.perform(get("/members/me/rewards/history"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items[0].id").isNumber())
			.andExpect(jsonPath("$.data.items[0].amount").isNumber())
			.andExpect(jsonPath("$.data.items[0].reason").value("RECORD"))
			.andExpect(jsonPath("$.data.items[0].balanceAfter").isNumber())
			.andExpect(jsonPath("$.data.items[0].createdAt").isString())
			.andExpect(jsonPath("$.data.nextCursor").isNumber());
	}

	@Test
	void welcome_keepsFieldsTheAppReads() throws Exception {
		given(rewardCommandService.claimWelcome(any())).willReturn(new RewardClaimResponse(false, List.of(
			RewardItemResponse.crumbs(new GrantResult(Outcome.GRANTED, 25, 0, 25), CrumbReason.WELCOME))));

		mockMvc.perform(post("/members/me/rewards/welcome"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.alreadyDone").isBoolean())
			.andExpect(jsonPath("$.data.rewards[0].type").value("CRUMBS"))
			.andExpect(jsonPath("$.data.rewards[0].amount").value(25))
			.andExpect(jsonPath("$.data.rewards[0].capped").value(false))
			// 요청 안에서 바로 준 보상에는 id 가 없다 — id 는 pendingRewards 에만 있다
			.andExpect(jsonPath("$.data.rewards[0].id").doesNotExist());
	}
}
