package sweetgirl.dataops.sync.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.LocalDateTime;
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

import sweetgirl.dataops.global.advice.GlobalExceptionHandler;
import sweetgirl.dataops.sync.code.SyncStateErrorCode;
import sweetgirl.dataops.sync.dto.response.DueStoreResponse;
import sweetgirl.dataops.sync.dto.response.ObservationSampleResponse;
import sweetgirl.dataops.sync.dto.response.SeedResultResponse;
import sweetgirl.dataops.sync.dto.response.SyncStateResponse;
import sweetgirl.dataops.sync.enums.SyncSource;
import sweetgirl.dataops.sync.enums.SyncStateStatus;
import sweetgirl.dataops.sync.enums.SyncTier;
import sweetgirl.dataops.sync.exception.SyncStateException;
import sweetgirl.dataops.sync.service.SyncSampleService;
import sweetgirl.dataops.sync.service.SyncSeedService;
import sweetgirl.dataops.sync.service.SyncStateCommandService;
import sweetgirl.dataops.sync.service.SyncStateQueryService;

@ExtendWith(MockitoExtension.class)
class SyncStateControllerTest {

	private MockMvc mockMvc;

	@Mock
	private SyncStateCommandService syncStateCommandService;
	@Mock
	private SyncStateQueryService syncStateQueryService;
	@Mock
	private SyncSeedService syncSeedService;
	@Mock
	private SyncSampleService syncSampleService;

	@InjectMocks
	private SyncStateController syncStateController;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders
			.standaloneSetup(syncStateController)
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	private SyncStateResponse createResponse(SyncStateStatus state) {
		return new SyncStateResponse(
			1L, 10L, SyncSource.NAVER, "naver-123", SyncTier.T1, state,
			LocalDateTime.now(), null, null, 0, 0, 0);
	}

	@Test
	void exportDue_워커_워크리스트를_반환한다() throws Exception {
		given(syncStateCommandService.exportDue(50))
			.willReturn(List.of(new DueStoreResponse(1L, 10L, SyncSource.NAVER, "naver-123", 7L)));

		mockMvc.perform(get("/api/sync-states/due"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data[0].syncStateId").value(1))
			.andExpect(jsonPath("$.data[0].externalId").value("naver-123"))
			// 워커가 제출에 실어 보내면 늦게 도착한 이전 시도를 가려낼 수 있다
			.andExpect(jsonPath("$.data[0].attemptSeq").value(7));
	}

	@Test
	void reportFailure_백오프_결과를_반환한다() throws Exception {
		given(syncStateCommandService.reportFailure(1L)).willReturn(createResponse(SyncStateStatus.ACTIVE));

		mockMvc.perform(post("/api/sync-states/1/failure"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.state").value("ACTIVE"));
	}

	@Test void selected_store_filter_is_forwarded_without_claiming_global_backlog() throws Exception {
		given(syncStateCommandService.exportSelectedDue(2, List.of(102848L, 123631L))).willReturn(List.of());
		mockMvc.perform(get("/api/sync-states/due").param("limit", "2").param("storeIds", "102848,123631"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
		then(syncStateCommandService).should(never()).exportDue(anyInt());
	}

	@Test void invalid_selected_limit_returns_http_400_and_sync_error_code() throws Exception {
		given(syncStateCommandService.exportSelectedDue(51, List.of(1L)))
			.willThrow(new SyncStateException(SyncStateErrorCode.INVALID_DUE_SELECTION));
		mockMvc.perform(get("/api/sync-states/due").param("limit", "51").param("storeIds", "1"))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_DUE_SELECTION"));
		then(syncStateCommandService).should(never()).exportDue(anyInt());
	}

	@Test
	void seed_시딩_결과를_반환한다() throws Exception {
		given(syncSeedService.seed()).willReturn(new SeedResultResponse(30944, 30944, 0, 64));

		mockMvc.perform(post("/api/sync-states/seed"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.created").value(30944))
			.andExpect(jsonPath("$.data.provenanceBackfilled").value(64));
	}

	@Test
	void selectObservationSample_표본_구성을_반환한다() throws Exception {
		given(syncSampleService.selectObservationSample(1000))
			.willReturn(new ObservationSampleResponse(1000, 800, 200, 1000, 29980));

		mockMvc.perform(post("/api/sync-states/observation-sample"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.menuZeroCount").value(800))
			.andExpect(jsonPath("$.data.onHold").value(29980));
	}

	@Test
	void park_허용되지_않는_전이는_400_이다() throws Exception {
		given(syncStateCommandService.park(1L))
			.willThrow(new SyncStateException(SyncStateErrorCode.SYNC_STATE_TRANSITION_NOT_ALLOWED));

		mockMvc.perform(patch("/api/sync-states/1/park"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("SYNC_STATE_TRANSITION_NOT_ALLOWED"));
	}

	@Test
	void changeTier_요청_본문의_티어로_변경한다() throws Exception {
		given(syncStateCommandService.changeTier(eq(1L), eq(SyncTier.T0)))
			.willReturn(createResponse(SyncStateStatus.ACTIVE));

		mockMvc.perform(patch("/api/sync-states/1/tier")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"tier\":\"T0\"}"))
			.andExpect(status().isOk());

		then(syncStateCommandService).should().changeTier(1L, SyncTier.T0);
	}
}
