package sweetgirl.croissant.pat.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
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
import sweetgirl.croissant.pat.code.PatErrorCode;
import sweetgirl.croissant.pat.dto.response.PatListResponse;
import sweetgirl.croissant.pat.exception.PatException;
import sweetgirl.croissant.pat.service.PatCommandService;
import sweetgirl.croissant.pat.service.PatQueryService;

@ExtendWith(MockitoExtension.class)
@DisplayName("PatController")
class PatControllerTest {

	private MockMvc mockMvc;

	@Mock
	private PatCommandService patCommandService;
	@Mock
	private PatQueryService patQueryService;

	@InjectMocks
	private PatController patController;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(patController)
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	@Test
	@DisplayName("POST /members/{id}/pat — 200")
	void pat() throws Exception {
		mockMvc.perform(post("/members/2/pat"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true));
	}

	@Test
	@DisplayName("POST /members/{id}/pat — 차단 관계면 403 PAT403_BLOCKED")
	void patBlocked() throws Exception {
		willThrow(new PatException(PatErrorCode.PAT_BLOCKED)).given(patCommandService).pat(any(), any());

		mockMvc.perform(post("/members/2/pat"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("PAT403_BLOCKED"));
	}

	@Test
	@DisplayName("GET /members/me/pats — today 요약과 목록")
	void received() throws Exception {
		given(patQueryService.getReceived(any(), anyInt())).willReturn(new PatListResponse(
			new PatListResponse.TodaySummary(3L, "회원3", 2),
			List.of(new PatListResponse.PatResponse(LocalDate.of(2026, 10, 22), 3L, "회원3", "CROISSANT", null))));

		mockMvc.perform(get("/members/me/pats").param("days", "7"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.today.firstNickname").value("회원3"))
			.andExpect(jsonPath("$.data.today.count").value(2))
			.andExpect(jsonPath("$.data.items[0].memberId").value(3))
			.andExpect(jsonPath("$.data.items[0].date").value("2026-10-22"));
	}
}
