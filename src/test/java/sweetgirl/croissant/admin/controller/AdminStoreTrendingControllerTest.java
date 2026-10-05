package sweetgirl.croissant.admin.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import sweetgirl.croissant.global.advice.GlobalExceptionHandler;
import sweetgirl.croissant.store.code.StoreErrorCode;
import sweetgirl.croissant.store.exception.StoreException;
import sweetgirl.croissant.store.service.StoreTrendingBaselineCommandService;
import sweetgirl.croissant.store.service.trending.TrendingRankingService;

@ExtendWith(MockitoExtension.class)
class AdminStoreTrendingControllerTest {

	MockMvc mockMvc;

	@Mock
	StoreTrendingBaselineCommandService baselineCommandService;
	@Mock
	TrendingRankingService rankingService;

	@InjectMocks
	AdminStoreTrendingController controller;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(controller)
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	@Test
	void refreshBaselines_returnsUpdatedStoreCount() throws Exception {
		given(baselineCommandService.refreshBaselines(any(LocalDate.class))).willReturn(321);

		mockMvc.perform(post("/admin/search/trending/baseline/refresh"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.updatedStoreCount").value(321));
	}

	@Test
	void refreshBaselines_returns409WhenAnotherRefreshIsRunning() throws Exception {
		given(baselineCommandService.refreshBaselines(any(LocalDate.class)))
			.willThrow(new StoreException(StoreErrorCode.TRENDING_BASELINE_REFRESH_ALREADY_RUNNING));

		mockMvc.perform(post("/admin/search/trending/baseline/refresh"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("STORE409_6"));
	}

	@Test
	void refreshRanking_returnsPublishedStoreCount() throws Exception {
		given(rankingService.refresh(any(LocalDateTime.class))).willReturn(4);

		mockMvc.perform(post("/admin/search/trending/ranking/refresh"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.updatedStoreCount").value(4));
	}
}
