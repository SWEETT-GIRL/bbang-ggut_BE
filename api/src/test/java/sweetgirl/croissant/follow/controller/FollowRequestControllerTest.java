package sweetgirl.croissant.follow.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
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
import sweetgirl.croissant.follow.dto.response.FollowRequestResponse;
import sweetgirl.croissant.follow.exception.FollowException;
import sweetgirl.croissant.follow.service.FollowRequestCommandService;
import sweetgirl.croissant.follow.service.FollowRequestQueryService;
import sweetgirl.croissant.global.advice.GlobalExceptionHandler;
import sweetgirl.croissant.global.response.SliceResponse;
import sweetgirl.croissant.member.code.MemberErrorCode;
import sweetgirl.croissant.member.exception.MemberException;

@ExtendWith(MockitoExtension.class)
@DisplayName("FollowRequestController")
class FollowRequestControllerTest {

	private MockMvc mockMvc;

	@Mock
	private FollowRequestQueryService followRequestQueryService;
	@Mock
	private FollowRequestCommandService followRequestCommandService;

	@InjectMocks
	private FollowRequestController controller;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(controller)
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	@Test
	@DisplayName("GET /members/me/follow-requests — page · size 기본값 0 · 20")
	void received() throws Exception {
		given(followRequestQueryService.getReceived(null, 0, 20)).willReturn(new SliceResponse<>(
			List.of(new FollowRequestResponse(2L, "빵순이", "dog", "world/dog/front.png",
				LocalDateTime.of(2026, 11, 4, 10, 0))),
			false));

		mockMvc.perform(get("/members/me/follow-requests"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.content[0].memberId").value(2))
			.andExpect(jsonPath("$.data.content[0].nickname").value("빵순이"))
			.andExpect(jsonPath("$.data.hasNext").value(false));
	}

	@Test
	@DisplayName("GET /members/me/follow-requests/count")
	void count() throws Exception {
		given(followRequestQueryService.countReceived(null)).willReturn(3L);

		mockMvc.perform(get("/members/me/follow-requests/count"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.count").value(3));
	}

	@Test
	@DisplayName("POST /members/{id}/follow-requests/accept · reject — 200")
	void acceptAndReject() throws Exception {
		mockMvc.perform(post("/members/2/follow-requests/accept")).andExpect(status().isOk());
		then(followRequestCommandService).should().accept(null, 2L);

		mockMvc.perform(post("/members/2/follow-requests/reject")).andExpect(status().isOk());
		then(followRequestCommandService).should().reject(null, 2L);
	}

	@Test
	@DisplayName("요청이 없으면 404 FOLLOW404_REQUEST, 게스트는 403 MEMBER403_GUEST")
	void errors() throws Exception {
		willThrow(new FollowException(FollowErrorCode.FOLLOW_REQUEST_NOT_FOUND))
			.given(followRequestCommandService).accept(null, 2L);
		mockMvc.perform(post("/members/2/follow-requests/accept"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("FOLLOW404_REQUEST"));

		given(followRequestQueryService.getReceived(null, 0, 20))
			.willThrow(new MemberException(MemberErrorCode.GUEST_LOGIN_REQUIRED));
		mockMvc.perform(get("/members/me/follow-requests"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("MEMBER403_GUEST"));
	}
}
