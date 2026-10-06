package sweetgirl.croissant.monthlyreport;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import sweetgirl.croissant.monthlyreport.admin.controller.AdminMonthlyReportController;
import sweetgirl.croissant.monthlyreport.admin.service.MonthlyReportAdminCommandService;
import sweetgirl.croissant.monthlyreport.admin.service.MonthlyReportAdminQueryService;
import sweetgirl.croissant.monthlyreport.admin.service.MonthlyReportAssetCommandService;
import sweetgirl.croissant.monthlyreport.admin.service.MonthlyReportAssetQueryService;
import sweetgirl.croissant.monthlyreport.admin.service.MonthlyReportAssetUploadService;

class AdminMonthlyReportControllerTest {
	private final MonthlyReportAdminCommandService reports = mock(MonthlyReportAdminCommandService.class);
	private final MockMvc mvc = MockMvcBuilders
		.standaloneSetup(new AdminMonthlyReportController(reports, mock(MonthlyReportAdminQueryService.class),
			mock(MonthlyReportAssetUploadService.class), mock(MonthlyReportAssetQueryService.class),
			mock(MonthlyReportAssetCommandService.class)))
		.build();

	@Test
	void singleMemberRouteCannotInvokeAllMemberCreation() throws Exception {
		mvc.perform(post("/api/admin/monthly-reports/2026-09/members/123/runs")
			.contentType(MediaType.APPLICATION_JSON).content("{\"assetVersion\":\"v1\"}"))
			.andExpect(status().isAccepted());
		verify(reports).createReportRun("2026-09", "v1", 123L);
		verify(reports, never()).createReportRun("2026-09", "v1");
	}

	@Test
	void singleMemberRouteValidatesVersionBeforeCreatingRun() throws Exception {
		mvc.perform(post("/api/admin/monthly-reports/2026-09/members/123/runs")
			.contentType(MediaType.APPLICATION_JSON).content("{\"assetVersion\":\"../v1\"}"))
			.andExpect(status().isBadRequest());
		verify(reports, never()).createReportRun("2026-09", "../v1", 123L);
	}
}
