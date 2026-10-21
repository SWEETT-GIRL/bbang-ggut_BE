package sweetgirl.croissant.legacy.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import sweetgirl.croissant.global.advice.GlobalExceptionHandler;

class LegacyBadgeMissionControllerTest {

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(new LegacyBadgeMissionController())
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	@Test
	@DisplayName("1.5.x 앱이 부르는 지워진 뱃지·미션 API 는 빈 응답 200 — 404 가 아니다")
	void legacyEndpointsReturnEmpty() throws Exception {
		mockMvc.perform(get("/members/me/badges"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data").isEmpty());
		mockMvc.perform(get("/members/me/missions"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data").isEmpty());
		mockMvc.perform(post("/members/me/badges/FIRST_RECORD/celebrate"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true));
	}
}
