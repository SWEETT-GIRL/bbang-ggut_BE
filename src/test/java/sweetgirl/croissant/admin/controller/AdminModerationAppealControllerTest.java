package sweetgirl.croissant.admin.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import sweetgirl.croissant.admin.dto.response.AdminModerationAppealResponse;
import sweetgirl.croissant.admin.service.AdminModerationAppealService;
import sweetgirl.croissant.global.advice.GlobalExceptionHandler;

@ExtendWith(MockitoExtension.class)
class AdminModerationAppealControllerTest {

	private MockMvc mockMvc;

	@Mock
	private AdminModerationAppealService adminModerationAppealService;

	@InjectMocks
	private AdminModerationAppealController adminModerationAppealController;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders
			.standaloneSetup(adminModerationAppealController)
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	@Test
	void getAppeals_returnsAppealPage() throws Exception {
		given(adminModerationAppealService.getAppeals(eq("PENDING"), eq(0), eq(20)))
			.willReturn(new PageImpl<>(List.of(response("PENDING", null)), PageRequest.of(0, 20), 1));

		mockMvc.perform(get("/api/admin/moderation-appeals")
			.param("status", "PENDING"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.content[0].appealId").value(1))
			.andExpect(jsonPath("$.data.content[0].status").value("PENDING"));
	}

	@Test
	void reviewAppeal_returnsReviewResult() throws Exception {
		given(adminModerationAppealService.reviewAppeal(eq(1L), any()))
			.willReturn(response("ACCEPTED", "복구 처리"));

		mockMvc.perform(patch("/api/admin/moderation-appeals/1")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"action\":\"ACCEPT\",\"adminMemo\":\"복구 처리\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.appealId").value(1))
			.andExpect(jsonPath("$.data.status").value("ACCEPTED"))
			.andExpect(jsonPath("$.data.adminMemo").value("복구 처리"));
	}

	private AdminModerationAppealResponse response(String status, String adminMemo) {
		return new AdminModerationAppealResponse(
			1L,
			55L,
			20L,
			"REVIEW",
			123L,
			"DELETE_REVIEW",
			"욕설 포함",
			"이의제기합니다.",
			status,
			adminMemo,
			null,
			"2026-06-05T10:00",
			null);
	}
}
