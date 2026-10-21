package sweetgirl.croissant.follow.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

import sweetgirl.croissant.follow.code.FollowErrorCode;
import sweetgirl.croissant.follow.dto.response.FollowMemberResponse;
import sweetgirl.croissant.follow.exception.FollowException;
import sweetgirl.croissant.follow.service.FollowCommandService;
import sweetgirl.croissant.follow.service.FollowQueryService;
import sweetgirl.croissant.global.advice.GlobalExceptionHandler;
import sweetgirl.croissant.global.response.SliceResponse;
import sweetgirl.croissant.member.code.MemberErrorCode;
import sweetgirl.croissant.member.exception.MemberException;

@ExtendWith(MockitoExtension.class)
@DisplayName("FollowController")
class FollowControllerTest {

	private MockMvc mockMvc;

	@Mock
	private FollowCommandService followCommandService;
	@Mock
	private FollowQueryService followQueryService;

	@InjectMocks
	private FollowController followController;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(followController)
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	@Test
	@DisplayName("POST /members/{id}/follow — 200")
	void follow() throws Exception {
		mockMvc.perform(post("/members/2/follow"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true));
		then(followCommandService).should().follow(null, 2L);
	}

	@Test
	@DisplayName("DELETE /members/{id}/follow — 200")
	void unfollow() throws Exception {
		mockMvc.perform(delete("/members/2/follow"))
			.andExpect(status().isOk());
		then(followCommandService).should().unfollow(null, 2L);
	}

	@Test
	@DisplayName("하루 상한은 429 FOLLOW429_DAILY_LIMIT, 게스트는 403 MEMBER403_GUEST")
	void errors() throws Exception {
		willThrow(new FollowException(FollowErrorCode.FOLLOW_DAILY_LIMIT))
			.given(followCommandService).follow(any(), eq(2L));
		mockMvc.perform(post("/members/2/follow"))
			.andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.code").value("FOLLOW429_DAILY_LIMIT"));

		willThrow(new MemberException(MemberErrorCode.GUEST_LOGIN_REQUIRED))
			.given(followCommandService).follow(any(), eq(3L));
		mockMvc.perform(post("/members/3/follow"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("MEMBER403_GUEST"));
	}

	@Test
	@DisplayName("GET /members/{id}/followers — page · size 기본값 0 · 20")
	void followers() throws Exception {
		given(followQueryService.getFollowers(null, 2L, 0, 20))
			.willReturn(new SliceResponse<>(List.of(new FollowMemberResponse(10L, "에이", true, false)), false));

		mockMvc.perform(get("/members/2/followers"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.content[0].memberId").value(10))
			.andExpect(jsonPath("$.data.content[0].followedByMe").value(true))
			.andExpect(jsonPath("$.data.hasNext").value(false));
	}

	@Test
	@DisplayName("GET /members/{id}/followings — 차단 관계면 403 FOLLOW403_BLOCKED")
	void followingsBlocked() throws Exception {
		given(followQueryService.getFollowings(null, 2L, 1, 10))
			.willThrow(new FollowException(FollowErrorCode.FOLLOW_BLOCKED));

		mockMvc.perform(get("/members/2/followings").param("page", "1").param("size", "10"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("FOLLOW403_BLOCKED"));
	}
}
