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

import sweetgirl.croissant.breadranking.dto.response.BreadBoardEntryResponse;
import sweetgirl.croissant.breadranking.dto.response.BreadBoardResponse;
import sweetgirl.croissant.breadranking.dto.response.BreadCategoryResponse;
import sweetgirl.croissant.breadranking.dto.response.BreadRatingChipsResponse.Chip;
import sweetgirl.croissant.breadranking.dto.response.StoreSummaryResponse;
import sweetgirl.croissant.breadranking.service.BreadBoardQueryService;
import sweetgirl.croissant.global.advice.GlobalExceptionHandler;

@ExtendWith(MockitoExtension.class)
class BreadBoardControllerTest {

	private static final BreadCategoryResponse SALT = new BreadCategoryResponse(1L, "소금빵", "소금빵", "빵", true, 48);

	private MockMvc mockMvc;

	@Mock
	BreadBoardQueryService boardQueryService;

	@InjectMocks
	BreadBoardController controller;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(controller)
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	@Test
	@DisplayName("GET /bread-rankings/categories — 탭 목록")
	void getBoardCategories_returns200() throws Exception {
		given(boardQueryService.getBoardCategories()).willReturn(List.of(SALT));

		mockMvc.perform(get("/bread-rankings/categories"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data[0].name").value("소금빵"))
			.andExpect(jsonPath("$.data[0].voters").value(48));
	}

	@Test
	@DisplayName("GET /bread-rankings/{categoryId}?limit= — 판")
	void getBoard_returns200() throws Exception {
		given(boardQueryService.getBoard(1L, 10)).willReturn(new BreadBoardResponse(SALT, 8, 3, 0, List.of(
			new BreadBoardEntryResponse(1, new StoreSummaryResponse(10L, "밀도 성수", null, "서울 성동구 성수동"), 5, 0.75,
				new Chip("겉바속촉", "겉바속촉"), 1, false))));

		mockMvc.perform(get("/bread-rankings/boards/1").param("limit", "10"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.totalVotes").value(8))
			.andExpect(jsonPath("$.data.entries[0].rank").value(1))
			.andExpect(jsonPath("$.data.entries[0].voters").value(5))
			.andExpect(jsonPath("$.data.entries[0].topTexture.code").value("겉바속촉"))
			.andExpect(jsonPath("$.data.entries[0].rankChange").value(1));
	}

	@Test
	@DisplayName("GET /bread-rankings/{categoryId} — 안 열린 판은 모자란 표 수")
	void getBoard_notOpen_returns200() throws Exception {
		given(boardQueryService.getBoard(1L, null)).willReturn(new BreadBoardResponse(SALT, 2, 3, 1, List.of()));

		mockMvc.perform(get("/bread-rankings/boards/1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.missingVotes").value(1))
			.andExpect(jsonPath("$.data.entries").isEmpty());
	}
}
