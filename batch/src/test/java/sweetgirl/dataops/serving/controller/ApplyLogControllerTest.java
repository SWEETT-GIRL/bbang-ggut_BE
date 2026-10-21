package sweetgirl.dataops.serving.controller;

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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import sweetgirl.dataops.global.advice.GlobalExceptionHandler;
import sweetgirl.dataops.global.response.PageResponse;
import sweetgirl.dataops.serving.dto.response.ApplyLogResponse;
import sweetgirl.dataops.serving.enums.ApplyOrigin;
import sweetgirl.dataops.serving.service.ApplyLogQueryService;

@ExtendWith(MockitoExtension.class)
class ApplyLogControllerTest {

	private MockMvc mockMvc;

	@Mock
	private ApplyLogQueryService applyLogQueryService;

	@InjectMocks
	private ApplyLogController applyLogController;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders
			.standaloneSetup(applyLogController)
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	@Test
	void getApplyLogs_감사_로그를_페이지로_반환한다() throws Exception {
		ApplyLogResponse response = new ApplyLogResponse(
			1L, 5L, "store.phone", "\"02-111-2222\"", "\"02-333-4444\"",
			ApplyOrigin.AUTO, null, 7L, LocalDateTime.now());
		given(applyLogQueryService.getApplyLogs(isNull(), eq(0), eq(20)))
			.willReturn(new PageResponse<>(List.of(response), 0, 20, 1, 1));

		mockMvc.perform(get("/api/apply-logs"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.content[0].target").value("store.phone"))
			.andExpect(jsonPath("$.data.content[0].origin").value("AUTO"))
			.andExpect(jsonPath("$.data.totalElements").value(1));
	}

	@Test
	void getApplyLogs_storeId_필터를_서비스에_전달한다() throws Exception {
		given(applyLogQueryService.getApplyLogs(eq(5L), eq(0), eq(20)))
			.willReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));

		mockMvc.perform(get("/api/apply-logs").param("storeId", "5"))
			.andExpect(status().isOk());

		then(applyLogQueryService).should().getApplyLogs(eq(5L), eq(0), eq(20));
	}
}
