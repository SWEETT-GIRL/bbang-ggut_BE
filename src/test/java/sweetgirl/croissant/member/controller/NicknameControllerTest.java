package sweetgirl.croissant.member.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import sweetgirl.croissant.global.advice.GlobalExceptionHandler;
import sweetgirl.croissant.member.code.NicknameErrorCode;
import sweetgirl.croissant.member.dto.response.MemberProfileResponse;
import sweetgirl.croissant.member.dto.response.NicknameAvailabilityResponse;
import sweetgirl.croissant.member.exception.NicknameException;
import sweetgirl.croissant.member.service.NicknameCommandService;
import sweetgirl.croissant.member.service.NicknameQueryService;

@ExtendWith(MockitoExtension.class)
@DisplayName("NicknameController")
class NicknameControllerTest {

	private MockMvc mockMvc;

	@Mock
	private NicknameCommandService nicknameCommandService;
	@Mock
	private NicknameQueryService nicknameQueryService;

	@InjectMocks
	private NicknameController nicknameController;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(nicknameController)
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	@Test
	@DisplayName("PATCH /users/me/nickname — 바뀐 프로필을 돌려준다")
	void change() throws Exception {
		given(nicknameCommandService.change(any(), eq("빵순이")))
			.willReturn(new MemberProfileResponse(1L, "빵순이", null, false, null));

		mockMvc.perform(patch("/users/me/nickname")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"nickname\":\"빵순이\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.nickname").value("빵순이"));
	}

	@Test
	@DisplayName("중복은 409 NICKNAME409_TAKEN, 빈 본문은 400")
	void errors() throws Exception {
		given(nicknameCommandService.change(any(), eq("빵순이")))
			.willThrow(new NicknameException(NicknameErrorCode.NICKNAME_TAKEN));

		mockMvc.perform(patch("/users/me/nickname")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"nickname\":\"빵순이\"}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("NICKNAME409_TAKEN"));
		mockMvc.perform(patch("/users/me/nickname")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"nickname\":\"  \"}"))
			.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("GET /users/me/nickname/availability — 후보까지 돌려준다")
	void availability() throws Exception {
		given(nicknameQueryService.checkAvailability("빵순이"))
			.willReturn(NicknameAvailabilityResponse.rejected("TAKEN", List.of("빵순이01", "빵순이27", "빵순이88")));

		mockMvc.perform(get("/users/me/nickname/availability").param("nickname", "빵순이"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.available").value(false))
			.andExpect(jsonPath("$.data.reason").value("TAKEN"))
			.andExpect(jsonPath("$.data.suggestions.length()").value(3));
	}
}
