package sweetgirl.croissant.admin.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import sweetgirl.croissant.global.advice.GlobalExceptionHandler;
import sweetgirl.croissant.store.code.StoreErrorCode;
import sweetgirl.croissant.store.exception.StoreException;
import sweetgirl.croissant.store.service.StorePopularityCommandService;

@ExtendWith(MockitoExtension.class)
class AdminStorePopularityControllerTest {

	MockMvc mockMvc;

	@Mock
	StorePopularityCommandService storePopularityCommandService;

	@InjectMocks
	AdminStorePopularityController controller;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(controller)
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	@Test
	void refresh_returnsUpdatedStoreCount() throws Exception {
		given(storePopularityCommandService.refreshAllPopularityScores()).willReturn(45_339);

		mockMvc.perform(post("/admin/search/popularity/refresh"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.updatedStoreCount").value(45_339));
	}

	@Test
	void refresh_returns409WhenAnotherRefreshIsRunning() throws Exception {
		given(storePopularityCommandService.refreshAllPopularityScores())
			.willThrow(new StoreException(StoreErrorCode.POPULARITY_REFRESH_ALREADY_RUNNING));

		mockMvc.perform(post("/admin/search/popularity/refresh"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("STORE409_5"));
	}

	@Test
	void refresh_returns500WhenRefreshFails() throws Exception {
		given(storePopularityCommandService.refreshAllPopularityScores())
			.willThrow(new DataAccessResourceFailureException("db down"));

		mockMvc.perform(post("/admin/search/popularity/refresh"))
			.andExpect(status().is5xxServerError());
	}
}
