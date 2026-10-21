package sweetgirl.croissant.profile.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import sweetgirl.croissant.global.contract.ResponseContractWebMvcTest;
import sweetgirl.croissant.profile.dto.response.ProfileResponse;
import sweetgirl.croissant.profile.service.MemberSearchQueryService;
import sweetgirl.croissant.profile.service.MemberSuggestionQueryService;
import sweetgirl.croissant.profile.service.ProfileQueryService;

/** 프로필 응답 모양을 고정한다. 앱 사용처는 FE F1(남의 프로필, 피그마 01-A1)이 붙으면 파일:라인으로 적는다. 필드는 더하기만. */
@ContextConfiguration(classes = ProfileController.class)
class ProfileResponseContractTest extends ResponseContractWebMvcTest {

	@MockitoBean
	private ProfileQueryService profileQueryService;
	@MockitoBean
	private MemberSearchQueryService memberSearchQueryService;
	@MockitoBean
	private MemberSuggestionQueryService memberSuggestionQueryService;

	@Test
	void profile_keepsFieldsTheAppReads() throws Exception {
		given(profileQueryService.getProfile(any(), any())).willReturn(new ProfileResponse(
			2L, false, false, "빵순이", "dog", "world/dog/front.png", "lamp", 3, "화성", "성수동 빵순이",
			12, 30, 7, true, false));

		mockMvc.perform(get("/members/2/profile"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.memberId").isNumber())
			.andExpect(jsonPath("$.data.blocked").isBoolean())
			.andExpect(jsonPath("$.data.isMe").isBoolean())
			.andExpect(jsonPath("$.data.nickname").isString())
			.andExpect(jsonPath("$.data.characterCode").isString())
			.andExpect(jsonPath("$.data.characterImageKey").isString())
			.andExpect(jsonPath("$.data.homePropItem").isString())
			.andExpect(jsonPath("$.data.level").isNumber())
			.andExpect(jsonPath("$.data.planetName").isString())
			.andExpect(jsonPath("$.data.title").isString())
			.andExpect(jsonPath("$.data.publicRecordCount").isNumber())
			.andExpect(jsonPath("$.data.followerCount").isNumber())
			.andExpect(jsonPath("$.data.followingCount").isNumber())
			.andExpect(jsonPath("$.data.followedByMe").isBoolean())
			.andExpect(jsonPath("$.data.followsMe").isBoolean());
	}
}
