package sweetgirl.croissant.follow.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import sweetgirl.croissant.follow.dto.response.FollowMemberResponse;
import sweetgirl.croissant.follow.service.FollowCommandService;
import sweetgirl.croissant.follow.service.FollowQueryService;
import sweetgirl.croissant.global.contract.ResponseContractWebMvcTest;
import sweetgirl.croissant.global.response.SliceResponse;

/**
 * 팔로워 · 팔로잉 목록 응답 모양을 고정한다. 앱 사용처는 FE F1(팔로잉 · 팔로워 화면, 피그마 04)이 붙으면 파일:라인으로 적는다.
 * 필드는 더하기만 한다 — 캐릭터 · 레벨 · 칭호가 P1-b 에서 붙는다.
 */
@ContextConfiguration(classes = FollowController.class)
class FollowResponseContractTest extends ResponseContractWebMvcTest {

	@MockitoBean
	private FollowQueryService followQueryService;

	@MockitoBean
	private FollowCommandService followCommandService;

	@Test
	void followers_keepsFieldsTheAppReads() throws Exception {
		given(followQueryService.getFollowers(any(), any(), any(), anyInt(), anyInt()))
			.willReturn(new SliceResponse<>(
				List.of(
					new FollowMemberResponse(10L, "에이", true, false, "CROISSANT", "world/croissant.png", false, false)),
				true));

		mockMvc.perform(get("/members/2/followers"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.content").isArray())
			.andExpect(jsonPath("$.data.content[0].memberId").isNumber())
			.andExpect(jsonPath("$.data.content[0].nickname").isString())
			// FollowButton 상태 셋(팔로우 · 팔로잉 · 맞팔로우)이 이 둘로 갈린다
			.andExpect(jsonPath("$.data.content[0].followedByMe").isBoolean())
			.andExpect(jsonPath("$.data.content[0].followsMe").isBoolean())
			.andExpect(jsonPath("$.data.content[0].isPrivate").isBoolean())
			.andExpect(jsonPath("$.data.content[0].followRequestedByMe").isBoolean())
			.andExpect(jsonPath("$.data.hasNext").isBoolean());
	}
}
