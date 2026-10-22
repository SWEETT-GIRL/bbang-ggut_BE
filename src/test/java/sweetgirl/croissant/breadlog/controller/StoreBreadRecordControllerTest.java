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

import sweetgirl.croissant.breadlog.dto.response.StoreBreadRecordResponse;
import sweetgirl.croissant.breadlog.service.BreadRecordQueryService;
import sweetgirl.croissant.global.advice.GlobalExceptionHandler;
import sweetgirl.croissant.global.response.SliceResponse;

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
		given(queryService.getStorePublicRecords(eq(10L), isNull(), eq(0), eq(20)))
			.willReturn(new SliceResponse<>(List.of(response), false));

		mockMvc.perform(get("/stores/10/bread-records"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.content[0].authorNickname").value("달콤한 소금빵"))
			.andExpect(jsonPath("$.data.content[0].eatenOn").value("2026-08-02"))
			.andExpect(jsonPath("$.data.hasNext").value(false));
	}
}
