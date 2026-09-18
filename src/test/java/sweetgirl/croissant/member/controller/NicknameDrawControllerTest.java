package sweetgirl.croissant.member.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import sweetgirl.croissant.global.advice.GlobalExceptionHandler;
import sweetgirl.croissant.member.code.NicknameErrorCode;
import sweetgirl.croissant.member.dto.response.MemberProfileResponse;
import sweetgirl.croissant.member.dto.response.NicknameDrawResponse;
import sweetgirl.croissant.member.dto.response.NicknameDrawStatusResponse;
import sweetgirl.croissant.member.exception.NicknameException;
import sweetgirl.croissant.member.service.NicknameDrawService;

@ExtendWith(MockitoExtension.class)
class NicknameDrawControllerTest {

	private MockMvc mockMvc;

	@Mock
	private NicknameDrawService nicknameDrawService;

	@InjectMocks
	private NicknameDrawController nicknameDrawController;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders
			.standaloneSetup(nicknameDrawController)
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	@Test
	void getStatus_returns200() throws Exception {
		given(nicknameDrawService.getStatus(any())).willReturn(new NicknameDrawStatusResponse(true, null, null));

		mockMvc.perform(get("/users/me/nickname/draw"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.canDraw").value(true));
	}

	@Test
	void draw_returns200WithCandidate() throws Exception {
		given(nicknameDrawService.draw(any())).willReturn(new NicknameDrawResponse("달콤한 소금빵", "바삭한 츄러스"));

		mockMvc.perform(post("/users/me/nickname/draw"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.currentNickname").value("달콤한 소금빵"))
			.andExpect(jsonPath("$.data.candidate").value("바삭한 츄러스"));
	}

	@Test
	void draw_returns429_whenAlreadyDrawnToday() throws Exception {
		given(nicknameDrawService.draw(any())).willThrow(new NicknameException(NicknameErrorCode.NICKNAME_DRAW_LIMIT));

		mockMvc.perform(post("/users/me/nickname/draw"))
			.andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.code").value("NICKNAME429_DRAW_LIMIT"));
	}

	@Test
	void accept_returns409_whenTaken() throws Exception {
		given(nicknameDrawService.accept(any())).willThrow(new NicknameException(NicknameErrorCode.NICKNAME_TAKEN));

		mockMvc.perform(post("/users/me/nickname/draw/accept"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("NICKNAME409_TAKEN"));
	}

	@Test
	void discard_returns200WithCurrentNickname() throws Exception {
		given(nicknameDrawService.discard(any())).willReturn(new MemberProfileResponse(1L, "달콤한 소금빵"));

		mockMvc.perform(post("/users/me/nickname/draw/discard"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.nickname").value("달콤한 소금빵"));
	}
}
