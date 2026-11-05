package sweetgirl.croissant.breadlog.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import sweetgirl.croissant.breadlog.dto.response.StoreBreadRecordListResponse;
import sweetgirl.croissant.breadlog.dto.response.StoreBreadRecordResponse;
import sweetgirl.croissant.breadlog.enums.BreadRecordSort;
import sweetgirl.croissant.breadlog.service.BreadRecordQueryService;
import sweetgirl.croissant.global.advice.GlobalExceptionHandler;

@ExtendWith(MockitoExtension.class)
class StoreBreadRecordControllerTest {

	private MockMvc mockMvc;

	@Mock
	BreadRecordQueryService queryService;

	@InjectMocks
	StoreBreadRecordController controller;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(controller)
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	@Test
	void getStorePublicRecords_returns200() throws Exception {
		StoreBreadRecordResponse response = new StoreBreadRecordResponse(
			11L, 7L, "달콤한 소금빵", "맛있었다", List.of("소금빵"), LocalDate.of(2026, 8, 2), List.of(), List.of(), 2, 1,
			"dog", null);
		given(queryService.getStorePublicRecords(eq(10L), isNull(), eq(BreadRecordSort.LATEST), eq(0), eq(20)))
			.willReturn(new StoreBreadRecordListResponse(List.of(response), false, 12));

		mockMvc.perform(get("/stores/10/bread-records"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.content[0].authorNickname").value("달콤한 소금빵"))
			.andExpect(jsonPath("$.data.content[0].eatenOn").value("2026-08-02"))
			.andExpect(jsonPath("$.data.hasNext").value(false))
			.andExpect(jsonPath("$.data.totalCount").value(12));

		// 정렬을 고르면 그대로 넘긴다
		given(queryService.getStorePublicRecords(eq(10L), isNull(), eq(BreadRecordSort.RECOMMENDED), eq(0), eq(20)))
			.willReturn(new StoreBreadRecordListResponse(List.of(), false, 0));
		mockMvc.perform(get("/stores/10/bread-records").param("sort", "RECOMMENDED"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.totalCount").value(0));
	}
}
