package sweetgirl.croissant.profile.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

import sweetgirl.croissant.follow.dto.response.FollowMemberResponse;
import sweetgirl.croissant.global.advice.GlobalExceptionHandler;
import sweetgirl.croissant.profile.code.ProfileErrorCode;
import sweetgirl.croissant.profile.dto.response.ProfileResponse;
import sweetgirl.croissant.profile.dto.response.VisitedStoreResponse;
import sweetgirl.croissant.profile.exception.ProfileException;
import sweetgirl.croissant.profile.service.MemberSearchQueryService;
import sweetgirl.croissant.profile.service.MemberSuggestionQueryService;
import sweetgirl.croissant.profile.service.ProfileQueryService;
import sweetgirl.croissant.profile.service.VisitedStoreQueryService;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProfileController")
class ProfileControllerTest {

	private MockMvc mockMvc;

	@Mock
	private ProfileQueryService profileQueryService;
	@Mock
	private MemberSearchQueryService memberSearchQueryService;
	@Mock
	private MemberSuggestionQueryService memberSuggestionQueryService;
	@Mock
	private VisitedStoreQueryService visitedStoreQueryService;

	@InjectMocks
	private ProfileController profileController;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(profileController)
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	@Test
	@DisplayName("GET /members/{id}/profile — 차단이면 blocked 만 참, 없으면 404 PROFILE404_MEMBER")
	void profile() throws Exception {
		given(profileQueryService.getProfile(any(), eq(2L))).willReturn(ProfileResponse.blocked(2L));
		mockMvc.perform(get("/members/2/profile"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.blocked").value(true))
			.andExpect(jsonPath("$.data.nickname").doesNotExist());

		given(profileQueryService.getProfile(any(), eq(3L)))
			.willThrow(new ProfileException(ProfileErrorCode.PROFILE_MEMBER_NOT_FOUND));
		mockMvc.perform(get("/members/3/profile"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("PROFILE404_MEMBER"));
	}

	@Test
	@DisplayName("GET /members/search?q= 와 GET /members/suggestions")
	void searchAndSuggestions() throws Exception {
		given(memberSearchQueryService.search(any(), eq("빵순")))
			.willReturn(List.of(new FollowMemberResponse(10L, "빵순이", false, true, "CROISSANT", "world/croissant.png")));
		mockMvc.perform(get("/members/search").param("q", "빵순"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data[0].memberId").value(10))
			.andExpect(jsonPath("$.data[0].followsMe").value(true));

		given(memberSuggestionQueryService.suggest(any())).willReturn(List.of());
		mockMvc.perform(get("/members/suggestions"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data").isArray());
	}

	@Test
	@DisplayName("GET /members/{id}/visited-stores — 핀 목록")
	void visitedStores() throws Exception {
		given(visitedStoreQueryService.getVisitedStores(any(), eq(2L))).willReturn(List.of(
			new VisitedStoreResponse(5L, "밀도 성수", 37.54, 127.05, 3, "resized/320/a", List.of("소금빵", "식빵"))));

		mockMvc.perform(get("/members/2/visited-stores"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data[0].storeId").value(5))
			.andExpect(jsonPath("$.data[0].publicRecordCount").value(3))
			.andExpect(jsonPath("$.data[0].topBreads[0]").value("소금빵"));
	}
}
