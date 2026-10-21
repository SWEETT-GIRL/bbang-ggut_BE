package sweetgirl.dataops.snapshot.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
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
import sweetgirl.dataops.global.response.PageResponse;
import sweetgirl.dataops.snapshot.code.SnapshotErrorCode;
import sweetgirl.dataops.snapshot.dto.response.SnapshotResponse;
import sweetgirl.dataops.snapshot.dto.response.SnapshotSubmitResponse;
import sweetgirl.dataops.snapshot.exception.SnapshotException;
import sweetgirl.dataops.snapshot.service.SnapshotCommandService;
import sweetgirl.dataops.snapshot.service.SnapshotQueryService;
import sweetgirl.dataops.sync.enums.SyncSource;

@ExtendWith(MockitoExtension.class)
class SnapshotControllerTest {

	private MockMvc mockMvc;

	@Mock
	private SnapshotCommandService snapshotCommandService;
	@Mock
	private SnapshotQueryService snapshotQueryService;

	@InjectMocks
	private SnapshotController snapshotController;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders
			.standaloneSetup(snapshotController)
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	@Test
	void submit_원본을_제출하면_201_과_변경_여부를_반환한다() throws Exception {
		given(snapshotCommandService.submit(any()))
			.willReturn(new SnapshotSubmitResponse(10L, true, "a".repeat(64)));

		mockMvc.perform(post("/api/snapshots")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"storeId\":1,\"source\":\"NAVER\",\"payload\":{\"name\":\"빵집\"}}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.data.snapshotId").value(10))
			.andExpect(jsonPath("$.data.changed").value(true));
	}

	@Test
	void submit_필수_필드가_없으면_400_이다() throws Exception {
		mockMvc.perform(post("/api/snapshots")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"source\":\"NAVER\"}"))
			.andExpect(status().isBadRequest());
	}

	@Test
	void submit_accepts_observation_identity_and_offset_time() throws Exception {
		given(snapshotCommandService.submit(any())).willReturn(new SnapshotSubmitResponse(null, false, "a".repeat(64)));
		mockMvc.perform(post("/api/snapshots").contentType(MediaType.APPLICATION_JSON).content("""
			{"storeId":1,"source":"NAVER","payload":{},"observationId":"fetch-1",
			 "externalId":"123","observedAt":"2026-09-25T01:00:00+00:00"}
			""")).andExpect(status().isCreated());
		then(snapshotCommandService).should().submit(argThat(request -> "fetch-1".equals(request.observationId())
			&& "123".equals(request.externalId())
			&& OffsetDateTime.parse("2026-09-25T01:00:00Z").equals(request.observedAt())));
	}

	@Test
	void getSnapshots_가게별_이력을_반환한다() throws Exception {
		SnapshotResponse response = new SnapshotResponse(
			10L, 1L, SyncSource.NAVER, LocalDateTime.now(), "a".repeat(64));
		given(snapshotQueryService.getSnapshots(1L, 0, 20))
			.willReturn(new PageResponse<>(List.of(response), 0, 20, 1, 1));

		mockMvc.perform(get("/api/snapshots").param("storeId", "1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.content[0].snapshotId").value(10));
	}

	@Test
	void getSnapshot_없는_스냅샷은_404_다() throws Exception {
		given(snapshotQueryService.getSnapshot(99L))
			.willThrow(new SnapshotException(SnapshotErrorCode.SNAPSHOT_NOT_FOUND));

		mockMvc.perform(get("/api/snapshots/99"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("SNAPSHOT_NOT_FOUND"));
	}
}
