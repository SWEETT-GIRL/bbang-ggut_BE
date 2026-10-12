package sweetgirl.croissant.admin.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import sweetgirl.croissant.admin.dto.response.AdminS3ReconcileResponse;
import sweetgirl.croissant.admin.service.AdminImageCommandService;
import sweetgirl.croissant.admin.service.AdminImageQueryService;
import sweetgirl.croissant.admin.service.AdminS3ReconcileService;
import sweetgirl.croissant.external.aws.exception.S3ErrorCode;
import sweetgirl.croissant.external.aws.exception.S3StorageException;
import sweetgirl.croissant.global.advice.GlobalExceptionHandler;

@ExtendWith(MockitoExtension.class)
class AdminImageControllerTest {

	MockMvc mockMvc;

	@Mock
	AdminImageQueryService adminImageQueryService;
	@Mock
	AdminImageCommandService adminImageCommandService;
	@Mock
	AdminS3ReconcileService adminS3ReconcileService;

	@InjectMocks
	AdminImageController controller;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(controller)
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	private AdminS3ReconcileResponse response(boolean dryRun) {
		return AdminS3ReconcileResponse.builder()
			.dryRun(dryRun).s3KeyCount(10).dbKeyCount(8).orphanCount(3).deletedCount(0)
			.orphanKeys(List.of("stores/9/orphan")).missingCount(1).failedMarkedCount(0)
			.missingKeys(List.of("stores/1/missing")).scopes(List.of("stores")).orphanBytes(1050)
			.breakdown(List.of()).build();
	}

	@Test
	@DisplayName("POST /admin/images/reconcile — 아무 값도 안 주면 dry-run·전체 폴더·7일·1000개로 돈다")
	void reconcile_usesSafeDefaults() throws Exception {
		given(adminS3ReconcileService.reconcile(true, null, 7, 1000)).willReturn(response(true));

		mockMvc.perform(post("/admin/images/reconcile"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.dryRun").value(true))
			.andExpect(jsonPath("$.data.s3KeyCount").value(10))
			.andExpect(jsonPath("$.data.dbKeyCount").value(8))
			.andExpect(jsonPath("$.data.orphanCount").value(3))
			.andExpect(jsonPath("$.data.deletedCount").value(0))
			.andExpect(jsonPath("$.data.orphanKeys[0]").value("stores/9/orphan"))
			.andExpect(jsonPath("$.data.missingCount").value(1))
			.andExpect(jsonPath("$.data.failedMarkedCount").value(0))
			.andExpect(jsonPath("$.data.missingKeys[0]").value("stores/1/missing"))
			.andExpect(jsonPath("$.data.orphanBytes").value(1050));
	}

	@Test
	@DisplayName("POST /admin/images/reconcile — 폴더·유예 일수·상한을 그대로 넘긴다")
	void reconcile_passesOptions() throws Exception {
		given(adminS3ReconcileService.reconcile(false, "menus", 14, 60000)).willReturn(response(false));

		mockMvc.perform(post("/admin/images/reconcile")
			.param("dryRun", "false").param("scope", "menus")
			.param("minAgeDays", "14").param("maxDelete", "60000"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.dryRun").value(false));

		then(adminS3ReconcileService).should().reconcile(false, "menus", 14, 60000);
	}

	@Test
	@DisplayName("POST /admin/images/reconcile — 이미 도는 중이면 409")
	void reconcile_returns409WhenRunning() throws Exception {
		given(adminS3ReconcileService.reconcile(true, null, 7, 1000))
			.willThrow(new S3StorageException(S3ErrorCode.RECONCILE_ALREADY_RUNNING));

		mockMvc.perform(post("/admin/images/reconcile"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("S34006"));
	}

	@Test
	@DisplayName("GET /admin/images/reconcile/latest — 마지막 결과를 반환한다")
	void latest_returnsResult() throws Exception {
		given(adminS3ReconcileService.findLatestResult()).willReturn(Optional.of(response(true)));

		mockMvc.perform(get("/admin/images/reconcile/latest"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.orphanCount").value(3));
	}

	@Test
	@DisplayName("GET /admin/images/reconcile/latest — 끝난 검사가 없으면 404")
	void latest_returns404WhenNone() throws Exception {
		given(adminS3ReconcileService.findLatestResult()).willReturn(Optional.empty());

		mockMvc.perform(get("/admin/images/reconcile/latest"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("S34005"));
	}
}
