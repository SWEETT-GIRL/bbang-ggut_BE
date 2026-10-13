package sweetgirl.croissant.reward.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import sweetgirl.croissant.global.advice.GlobalExceptionHandler;
import sweetgirl.croissant.reward.code.RewardErrorCode;
import sweetgirl.croissant.reward.dto.response.RankingRewardResponse;
import sweetgirl.croissant.reward.dto.response.RewardClaimResponse;
import sweetgirl.croissant.reward.exception.RewardException;
import sweetgirl.croissant.reward.service.RankingRewardService;

@ExtendWith(MockitoExtension.class)
class RankingRewardControllerTest {

	private MockMvc mockMvc;

	@Mock
	private RankingRewardService rankingRewardService;

	@InjectMocks
	private RankingRewardController rankingRewardController;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(rankingRewardController)
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	@Test
	@DisplayName("받을 수 있는 보상을 내린다")
	void pending() throws Exception {
		given(rankingRewardService.getPending(any())).willReturn(List.of(new RankingRewardResponse(
			12L, LocalDate.of(2026, 10, 5), 1, 100, true, LocalDate.of(2026, 11, 8))));

		mockMvc.perform(get("/members/me/ranking-rewards/pending"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data[0].id").value(12))
			.andExpect(jsonPath("$.data[0].weekStart").value("2026-10-05"))
			.andExpect(jsonPath("$.data[0].rank").value(1))
			.andExpect(jsonPath("$.data[0].crumbs").value(100))
			.andExpect(jsonPath("$.data[0].crown").value(true))
			.andExpect(jsonPath("$.data[0].expiresOn").value("2026-11-08"));
	}

	@Test
	@DisplayName("이미 받은 것을 다시 누르면 에러가 아니라 alreadyDone 이다")
	void claimAgain() throws Exception {
		given(rankingRewardService.claim(any(), eq(12L))).willReturn(RewardClaimResponse.done());

		mockMvc.perform(post("/members/me/ranking-rewards/12/claim"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.alreadyDone").value(true));
	}

	@Test
	@DisplayName("기한이 지난 보상은 410 REWARD410_1")
	void claimExpired() throws Exception {
		given(rankingRewardService.claim(any(), eq(12L)))
			.willThrow(new RewardException(RewardErrorCode.REWARD_RANKING_REWARD_EXPIRED));

		mockMvc.perform(post("/members/me/ranking-rewards/12/claim"))
			.andExpect(status().isGone())
			.andExpect(jsonPath("$.code").value("REWARD410_1"));
	}
}
