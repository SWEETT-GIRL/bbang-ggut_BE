package sweetgirl.croissant.admin.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import sweetgirl.croissant.admin.dto.response.AdminAbuseReportDetailResponse;
import sweetgirl.croissant.admin.dto.response.AdminAbuseReportStatsResponse;
import sweetgirl.croissant.admin.dto.response.AdminHighPriorityReportResponse;
import sweetgirl.croissant.admin.dto.response.AdminModerationActionResponse;
import sweetgirl.croissant.admin.dto.response.AdminReportDetailResponse;
import sweetgirl.croissant.admin.dto.response.AdminReportResponse;
import sweetgirl.croissant.admin.service.AdminReportCommandService;
import sweetgirl.croissant.admin.service.AdminReportQueryService;
import sweetgirl.croissant.global.advice.GlobalExceptionHandler;

@ExtendWith(MockitoExtension.class)
class AdminReportControllerTest {

	private MockMvc mockMvc;

	@Mock
	private AdminReportQueryService adminReportQueryService;

	@Mock
	private AdminReportCommandService adminReportCommandService;

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
	void getReports_keepsLegacyContributionListShape() throws Exception {
		AdminReportResponse report = new AdminReportResponse(
			11L,
			"STORE_REPORT",
			101L,
			"소금빵집",
			201L,
			"ADDRESS",
			null,
			null,
			null,
			null,
			null,
			"PENDING",
			"주소 수정 요청",
			List.of("reports/stores/101/images/a.jpg"),
			null,
			"2026-06-12T09:30");
		given(adminReportQueryService.getReports("STORE_REPORT", "PENDING", 0, 20))
			.willReturn(new PageImpl<>(List.of(report), PageRequest.of(0, 20), 1));

		mockMvc.perform(get("/api/admin/reports")
			.param("kind", "STORE_REPORT")
			.param("status", "PENDING"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.content[0].id").value(11))
			.andExpect(jsonPath("$.data.content[0].kind").value("STORE_REPORT"))
			.andExpect(jsonPath("$.data.content[0].storeId").value(101))
			.andExpect(jsonPath("$.data.content[0].storeName").value("소금빵집"))
			.andExpect(jsonPath("$.data.content[0].memberId").value(201))
			.andExpect(jsonPath("$.data.content[0].requestType").value("ADDRESS"))
			.andExpect(jsonPath("$.data.content[0].status").value("PENDING"))
			.andExpect(jsonPath("$.data.content[0].message").value("주소 수정 요청"))
			.andExpect(jsonPath("$.data.content[0].images[0]").value("reports/stores/101/images/a.jpg"));
	}

	@Test
	void getAbuseReportStats_returnsStats() throws Exception {
		given(adminReportQueryService.getAbuseReportStats())
			.willReturn(new AdminAbuseReportStatsResponse(
				10,
				4,
				3,
				3,
				5,
				1,
				1,
				1,
				24,
				3,
				2,
				3,
				List.of("PRIVACY", "HATE", "SEXUAL")));

		mockMvc.perform(get("/api/admin/reports/stats"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.totalReportCount").value(10))
			.andExpect(jsonPath("$.data.pendingReportCount").value(4))
			.andExpect(jsonPath("$.data.pendingReviewHideCandidateCount").value(1))
			.andExpect(jsonPath("$.data.sensitiveReasonTypes[0]").value("PRIVACY"));
	}

	@Test
	void getHighPriorityReports_returnsCandidates() throws Exception {
		AdminHighPriorityReportResponse report = new AdminHighPriorityReportResponse(
			1L,
			"ABUSE_REPORT",
			"REVIEW",
			101L,
			10L,
			20L,
			"PRIVACY",
			"개인정보",
			"PENDING",
			"NONE",
			3,
			2,
			3,
			true,
			true,
			true,
			true,
			175,
			List.of("SENSITIVE_REASON_PRIORITY", "TEMPORARY_HIDE_CANDIDATE", "MEMBER_REVIEW_CANDIDATE"),
			"2026-06-05T12:00",
			null);
		given(adminReportQueryService.getHighPriorityReports("PENDING", 0, 20))
			.willReturn(new PageImpl<>(List.of(report), PageRequest.of(0, 20), 1));

		mockMvc.perform(get("/api/admin/reports/high-priority")
			.param("status", "PENDING"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.content[0].reportId").value(1))
			.andExpect(jsonPath("$.data.content[0].highPriority").value(true))
			.andExpect(jsonPath("$.data.content[0].temporaryHideCandidate").value(true))
			.andExpect(jsonPath("$.data.content[0].priorityReasons[0]").value("SENSITIVE_REASON_PRIORITY"));
	}

	@Test
	void getReportDetail_keepsLegacyContributionDetailShape() throws Exception {
		AdminReportResponse report = new AdminReportResponse(
			12L,
			"MENU_ADD_REPORT",
			101L,
			"소금빵집",
			202L,
			"MENU_ADD",
			null,
			null,
			null,
			null,
			null,
			"PENDING",
			"앙버터 / 4500원",
			List.of("reports/menus/12/images/a.jpg"),
			null,
			"2026-06-12T10:00");
		given(adminReportQueryService.getReportDetail(eq(12L), eq("MENU_ADD_REPORT")))
			.willReturn(AdminReportDetailResponse.ofReport(report));

		mockMvc.perform(get("/api/admin/reports/12")
			.param("kind", "MENU_ADD_REPORT"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.reportId").value(12))
			.andExpect(jsonPath("$.data.kind").value("MENU_ADD_REPORT"))
			.andExpect(jsonPath("$.data.requestType").value("MENU_ADD"))
			.andExpect(jsonPath("$.data.status").value("PENDING"))
			.andExpect(jsonPath("$.data.reporterId").value(202))
			.andExpect(jsonPath("$.data.target.id").value(12))
			.andExpect(jsonPath("$.data.target.kind").value("MENU_ADD_REPORT"))
			.andExpect(jsonPath("$.data.target.images[0]").value("reports/menus/12/images/a.jpg"));
	}

	@Test
	void getReportDetail_returnsAbuseReportDetail() throws Exception {
		AdminAbuseReportDetailResponse.ReviewTarget target = new AdminAbuseReportDetailResponse.ReviewTarget(
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
		given(adminReportQueryService.getReportDetail(eq(1L), eq("ABUSE_REPORT")))
			.willReturn(new AdminReportDetailResponse(
				1L,
				"ABUSE_REPORT",
				"REVIEW",
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
			.param("kind", "ABUSE_REPORT"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.reportId").value(1))
			.andExpect(jsonPath("$.data.targetType").value("REVIEW"))
			.andExpect(jsonPath("$.data.target.reviewId").value(123))
			.andExpect(jsonPath("$.data.target.menuName").value("소금빵"))
			.andExpect(jsonPath("$.data.target.images[0]").value("reviews/members/20/images/a.jpg"));
	}

	@Test
	void reviewReport_keepsLegacyReviewEndpoint() throws Exception {
		mockMvc.perform(patch("/api/admin/reports/12")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"kind\":\"STORE_REPORT\",\"action\":\"ACCEPT\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true));

		verify(adminReportCommandService).reviewReport(eq(12L), any());
	}

	@Test
	void applyModerationAction_returnsActionResult() throws Exception {
		given(adminReportCommandService.applyModerationAction(eq(1L), any()))
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
