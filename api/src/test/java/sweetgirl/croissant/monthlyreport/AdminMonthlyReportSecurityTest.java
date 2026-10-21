package sweetgirl.croissant.monthlyreport;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import sweetgirl.croissant.config.security.ApiAuthenticationEntryPoint;
import sweetgirl.croissant.config.security.SecurityConfig;
import sweetgirl.croissant.global.advice.GlobalExceptionHandler;
import sweetgirl.croissant.global.exception.CustomException;
import sweetgirl.croissant.letter.config.AdminApiKeyConfig;
import sweetgirl.croissant.member.jwt.JwtProvider;
import sweetgirl.croissant.member.oauth.OAuth2SuccessHandler;
import sweetgirl.croissant.member.service.CustomOAuth2UserService;
import sweetgirl.croissant.monthlyreport.admin.controller.AdminMonthlyReportController;
import sweetgirl.croissant.monthlyreport.admin.controller.AdminMonthlyReportOperationsController;
import sweetgirl.croissant.monthlyreport.admin.service.MonthlyReportAdminCommandService;
import sweetgirl.croissant.monthlyreport.admin.service.MonthlyReportAdminQueryService;
import sweetgirl.croissant.monthlyreport.admin.service.MonthlyReportAssetCommandService;
import sweetgirl.croissant.monthlyreport.admin.service.MonthlyReportAssetQueryService;
import sweetgirl.croissant.monthlyreport.admin.service.MonthlyReportAssetUploadService;
import sweetgirl.croissant.monthlyreport.admin.service.MonthlyReportOperationsCommandService;
import sweetgirl.croissant.monthlyreport.admin.service.MonthlyReportOperationsQueryService;
import sweetgirl.croissant.monthlyreport.code.MonthlyReportErrorCode;

@WebMvcTest(properties = {"admin.api-key=monthly-report-test-key", "oauth2.front-redirect-uri=bbanggut://login"})
@ContextConfiguration(classes = {AdminMonthlyReportController.class, AdminMonthlyReportOperationsController.class,
	SecurityConfig.class, ApiAuthenticationEntryPoint.class, AdminApiKeyConfig.class, GlobalExceptionHandler.class})
class AdminMonthlyReportSecurityTest {
	@MockitoBean
	private MonthlyReportAssetCommandService assetCommands;
	private static final String KEY = "monthly-report-test-key";
	private static final String ROOT = "/api/admin/monthly-reports/2026-09/runs/";
	@Autowired
	private MockMvc mvc;
	@MockitoBean
	private MonthlyReportAdminCommandService commands;
	@MockitoBean
	private MonthlyReportAdminQueryService queries;
	@MockitoBean
	private MonthlyReportAssetUploadService uploads;
	@MockitoBean
	private MonthlyReportAssetQueryService assets;
	@MockitoBean
	private MonthlyReportOperationsCommandService operationsCommands;
	@MockitoBean
	private MonthlyReportOperationsQueryService operationsQueries;
	@MockitoBean
	private JwtProvider jwtProvider;
	@MockitoBean
	private CustomOAuth2UserService oauthUserService;
	@MockitoBean
	private OAuth2SuccessHandler oauthSuccessHandler;
	@MockitoBean
	private ClientRegistrationRepository registrations;

	@Test
	void missingOrWrongAdminKeyCannotReachPublicationOrRetry() throws Exception {
		UUID id = UUID.randomUUID();
		for (String suffix : new String[] {"/publish", "/retry", "/members/123/push/retry"}) {
			mvc.perform(post(ROOT + id + suffix).contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isUnauthorized());
			mvc.perform(post(ROOT + id + suffix).header("X-Admin-Key", "wrong")
				.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isUnauthorized());
		}
		mvc.perform(get("/api/admin/monthly-reports/2026-09/runs"))
			.andExpect(status().isUnauthorized());
		verifyNoInteractions(commands, queries, operationsCommands);
	}

	@Test
	void authorizedPublicationAndRetryUseTheirOwnServices() throws Exception {
		UUID id = UUID.randomUUID();
		mvc.perform(post(ROOT + id + "/publish").header("X-Admin-Key", KEY)
			.contentType(MediaType.APPLICATION_JSON).content("{\"pushAt\":null}"))
			.andExpect(status().isAccepted());
		verify(commands).approveReportPublication("2026-09", id, null);
		mvc.perform(post(ROOT + id + "/members/123/push/retry").header("X-Admin-Key", KEY))
			.andExpect(status().isAccepted());
		verify(operationsCommands).retryFailedReportPush("2026-09", id, 123L);
	}

	@Test
	void conflictAndInvalidRequestsKeepCommonErrorContract() throws Exception {
		UUID id = UUID.randomUUID();
		doThrow(new CustomException(MonthlyReportErrorCode.ADMIN_CONFLICT))
			.when(commands).approveReportPublication(eq("2026-09"), eq(id), any());
		mvc.perform(post(ROOT + id + "/publish").header("X-Admin-Key", KEY)
			.contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MONTHLY_REPORT4091"));
		mvc.perform(post("/api/admin/monthly-reports/2026-09/runs").header("X-Admin-Key", KEY)
			.contentType(MediaType.APPLICATION_JSON).content("{\"assetVersion\":\"../invalid\"}"))
			.andExpect(status().isBadRequest());
	}

	@Test
	void assetMutationsRequireAdminKeyAndValidPayload() throws Exception {
		String path = "/api/admin/monthly-reports/assets";
		mvc.perform(patch(path).contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isUnauthorized());
		mvc.perform(delete(path).header("X-Admin-Key", "wrong").contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isUnauthorized());
		mvc.perform(patch(path).header("X-Admin-Key", KEY).contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isBadRequest());
		mvc.perform(delete(path).header("X-Admin-Key", KEY).contentType(MediaType.APPLICATION_JSON)
			.content("{\"assetId\":\"v1/FACE/cat//smile\",\"expectedImageKey\":\"old.png\"}"))
			.andExpect(status().isOk());
		verify(assetCommands).removeReportAsset(any());
		String replacement = """
				{"asset":{"version":"v1","kind":"FACE","characterCode":"cat","planetCode":"",
				"variant":"smile","contentType":"image/webp","imageKey":"new.webp"},
				"expectedImageKey":"old.png","applyToExistingReports":true}
				""";
		mvc.perform(patch(path).header("X-Admin-Key", KEY).contentType(MediaType.APPLICATION_JSON).content(replacement))
			.andExpect(status().isOk());
		verify(uploads).replaceReportAsset(any());
	}
}
