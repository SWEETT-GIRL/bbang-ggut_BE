package sweetgirl.croissant.admin.bulkimport.controller;

import static org.mockito.BDDMockito.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import sweetgirl.croissant.admin.bulkimport.entity.BulkImportProgress;
import sweetgirl.croissant.admin.bulkimport.enums.BulkImportStatus;
import sweetgirl.croissant.admin.bulkimport.service.BulkImportOrchestrator;
import sweetgirl.croissant.admin.bulkimport.service.BulkImportProgressService;
import sweetgirl.croissant.global.advice.GlobalExceptionHandler;

@ExtendWith(MockitoExtension.class)
class BulkImportControllerTest {

	private MockMvc mockMvc;

	@Mock
	private BulkImportOrchestrator orchestrator;

	@Mock
	private BulkImportProgressService progressService;

	@InjectMocks
	private BulkImportController bulkImportController;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders
			.standaloneSetup(bulkImportController)
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	// ── POST /api/admin/stores/bulk-import ────────────────────────────────────

	@Test
	void start_returns200WithCounts_andTriggersAsyncRun() throws Exception {
		given(orchestrator.tryStart()).willReturn(true);
		given(progressService.countTotal()).willReturn(20142L);
		given(progressService.countByStatus(BulkImportStatus.PENDING)).willReturn(19587L);
		given(progressService.countByStatus(BulkImportStatus.COMPLETED)).willReturn(555L);

		mockMvc.perform(post("/api/admin/stores/bulk-import"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.totalDongs").value(20142))
			.andExpect(jsonPath("$.data.pendingDongs").value(19587))
			.andExpect(jsonPath("$.data.completedDongs").value(555));

		verify(orchestrator).runImportAsync();
	}

	@Test
	void start_returns409_whenAlreadyRunning() throws Exception {
		given(orchestrator.tryStart()).willReturn(false);

		mockMvc.perform(post("/api/admin/stores/bulk-import"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.code").value("BULK409_1"));

		verify(orchestrator, never()).runImportAsync();
	}

	// ── GET /api/admin/stores/bulk-import/progress ────────────────────────────

	@Test
	void getProgress_returnsCurrentState() throws Exception {
		given(orchestrator.isRunning()).willReturn(true);
		given(progressService.countTotal()).willReturn(20142L);
		given(progressService.countByStatus(BulkImportStatus.COMPLETED)).willReturn(8732L);
		given(progressService.countByStatus(BulkImportStatus.FAILED)).willReturn(12L);
		given(progressService.countByStatus(BulkImportStatus.IN_PROGRESS)).willReturn(1L);
		given(progressService.countByStatus(BulkImportStatus.PENDING)).willReturn(11397L);
		given(progressService.sumInsertedCount()).willReturn(142583L);

		mockMvc.perform(get("/api/admin/stores/bulk-import/progress"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.running").value(true))
			.andExpect(jsonPath("$.data.totalDongs").value(20142))
			.andExpect(jsonPath("$.data.completedDongs").value(8732))
			.andExpect(jsonPath("$.data.failedDongs").value(12))
			.andExpect(jsonPath("$.data.inProgressDongs").value(1))
			.andExpect(jsonPath("$.data.pendingDongs").value(11397))
			.andExpect(jsonPath("$.data.totalInserted").value(142583));
	}

	// ── GET /api/admin/stores/bulk-import/failed ──────────────────────────────

	@Test
	void getFailed_returnsFailedDongs() throws Exception {
		BulkImportProgress progress = BulkImportProgress.of("서울특별시", "강남구", "역삼동");
		progress.markInProgress();
		progress.markFailed("Kakao API timeout");
		given(progressService.getFailed()).willReturn(List.of(progress));

		mockMvc.perform(get("/api/admin/stores/bulk-import/failed"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.length()").value(1))
			.andExpect(jsonPath("$.data[0].region1st").value("서울특별시"))
			.andExpect(jsonPath("$.data[0].region2nd").value("강남구"))
			.andExpect(jsonPath("$.data[0].region3rd").value("역삼동"))
			.andExpect(jsonPath("$.data[0].errorMessage").value("Kakao API timeout"));
	}

	@Test
	void getFailed_returnsEmptyArray_whenNoFailures() throws Exception {
		given(progressService.getFailed()).willReturn(List.of());

		mockMvc.perform(get("/api/admin/stores/bulk-import/failed"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.length()").value(0));
	}

	// ── POST /api/admin/stores/bulk-import/stop ───────────────────────────────

	@Test
	void stop_callsRequestStop_andReturnsOk() throws Exception {
		mockMvc.perform(post("/api/admin/stores/bulk-import/stop"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true));

		verify(orchestrator).requestStop();
	}
}
