package sweetgirl.croissant.admin.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import sweetgirl.croissant.admin.dto.response.AdminModerationActionResponse;
import sweetgirl.croissant.admin.dto.response.AdminUserReportDetailResponse;
import sweetgirl.croissant.admin.service.AdminReportService;
import sweetgirl.croissant.global.advice.GlobalExceptionHandler;

@ExtendWith(MockitoExtension.class)
class AdminReportControllerTest {

	private MockMvc mockMvc;

	@Mock
	private AdminReportService adminReportService;

	@InjectMocks
	private AdminReportController adminReportController;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders
			.standaloneSetup(adminReportController)
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	@Test
	void getReportDetail_returnsUserReportDetail() throws Exception {
		AdminUserReportDetailResponse.ReviewTarget target = new AdminUserReportDetailResponse.ReviewTarget(
			123L,
			3L,
			"소금빵",
			2L,
			"빵집",
			20L,
			BigDecimal.valueOf(4.5),
			"문제된 리뷰",
			List.of("reviews/members/20/images/a.jpg"),
			"2026-06-04T18:00",
			null,
			null,
			null);
		given(adminReportService.getReportDetail(eq(1L), eq("USER_REPORT")))
			.willReturn(new AdminUserReportDetailResponse(
				1L,
				"USER_REPORT",
				"REVIEW",
				123L,
				"ABUSE",
				"욕설",
				"PENDING",
				"NONE",
				10L,
				20L,
				"2026-06-04T19:30",
				null,
				target));

		mockMvc.perform(get("/api/admin/reports/1")
			.param("kind", "USER_REPORT"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.reportId").value(1))
			.andExpect(jsonPath("$.data.targetType").value("REVIEW"))
			.andExpect(jsonPath("$.data.target.reviewId").value(123))
			.andExpect(jsonPath("$.data.target.menuName").value("소금빵"))
			.andExpect(jsonPath("$.data.target.images[0]").value("reviews/members/20/images/a.jpg"));
	}

	@Test
	void applyModerationAction_returnsActionResult() throws Exception {
		given(adminReportService.applyModerationAction(eq(1L), any()))
			.willReturn(new AdminModerationActionResponse(
				10L,
				1L,
				"REVIEW",
				123L,
				20L,
				"HIDE_REVIEW",
				"욕설 포함",
				"2026-06-04T20:30"));

		mockMvc.perform(post("/api/admin/reports/1/moderation-actions")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"action\":\"HIDE_REVIEW\",\"reason\":\"욕설 포함\",\"notifyUser\":true}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.actionId").value(10))
			.andExpect(jsonPath("$.data.action").value("HIDE_REVIEW"))
			.andExpect(jsonPath("$.data.targetId").value(123));
	}
}
