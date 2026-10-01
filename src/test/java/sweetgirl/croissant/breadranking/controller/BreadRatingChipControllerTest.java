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

import sweetgirl.croissant.breadranking.dto.response.BreadRatingChipsResponse;
import sweetgirl.croissant.breadranking.dto.response.BreadRatingChipsResponse.Chip;
import sweetgirl.croissant.breadranking.service.BreadRatingChipQueryService;
import sweetgirl.croissant.global.advice.GlobalExceptionHandler;

@ExtendWith(MockitoExtension.class)
class BreadRatingChipControllerTest {

	private MockMvc mockMvc;

	@Mock
	BreadRatingChipQueryService chipQueryService;

	@InjectMocks
	BreadRatingChipController controller;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(controller)
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	@Test
	@DisplayName("GET /bread-rating-chips?menuKind= — 대분류와 칩 목록을 준다")
	void getChips_returns200() throws Exception {
		given(chipQueryService.getChips("소금빵")).willReturn(new BreadRatingChipsResponse("빵",
			List.of(new Chip("겉바속촉", "겉바속촉")), List.of(new Chip("짭짤", "짭짤"))));

		mockMvc.perform(get("/bread-rating-chips").param("menuKind", "소금빵"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.breadGroup").value("빵"))
			.andExpect(jsonPath("$.data.textures[0].code").value("겉바속촉"))
			.andExpect(jsonPath("$.data.tastes[0].label").value("짭짤"));
	}

	@Test
	@DisplayName("GET /bread-rating-chips — menuKind 없이도 된다")
	void getChips_withoutKind_returns200() throws Exception {
		given(chipQueryService.getChips(null)).willReturn(new BreadRatingChipsResponse(null, List.of(), List.of()));

		mockMvc.perform(get("/bread-rating-chips"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.breadGroup").isEmpty());
	}
}
