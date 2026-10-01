package sweetgirl.croissant.breadranking.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import sweetgirl.croissant.breadranking.dto.response.BreadCategoryResponse;
import sweetgirl.croissant.breadranking.dto.response.StoreBreadRatingResponse;
import sweetgirl.croissant.breadranking.dto.response.StoreBreadRatingResponse.TextureStat;
import sweetgirl.croissant.breadranking.service.StoreBreadRatingQueryService;
import sweetgirl.croissant.global.advice.GlobalExceptionHandler;

@ExtendWith(MockitoExtension.class)
class StoreBreadRatingControllerTest {

	private MockMvc mockMvc;

	@Mock
	StoreBreadRatingQueryService storeBreadRatingQueryService;

	@InjectMocks
	StoreBreadRatingController controller;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(controller)
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	@Test
	@DisplayName("GET /stores/{storeId}/bread-ratings — 종류별 평가")
	void getStoreRatings_returns200() throws Exception {
		given(storeBreadRatingQueryService.getStoreRatings(10L)).willReturn(List.of(new StoreBreadRatingResponse(
			new BreadCategoryResponse(1L, "소금빵", "소금빵", "빵", true, 0), 2, 6, 0.8,
			List.of(new TextureStat("바삭", "바삭", 0.8)), 100L, 3800)));

		mockMvc.perform(get("/stores/10/bread-ratings"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data[0].category.name").value("소금빵"))
			.andExpect(jsonPath("$.data[0].rank").value(2))
			.andExpect(jsonPath("$.data[0].textures[0].ratio").value(0.8))
			.andExpect(jsonPath("$.data[0].price").value(3800));
	}
}
