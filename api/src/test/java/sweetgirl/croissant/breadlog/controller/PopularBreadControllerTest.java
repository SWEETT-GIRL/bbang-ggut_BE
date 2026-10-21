package sweetgirl.croissant.breadlog.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import sweetgirl.croissant.breadlog.dto.response.PopularBreadResponse;
import sweetgirl.croissant.breadlog.service.PopularBreadQueryService;

@ExtendWith(MockitoExtension.class)
class PopularBreadControllerTest {

	private MockMvc mockMvc;

	@Mock
	private PopularBreadQueryService popularBreadQueryService;

	@InjectMocks
	private PopularBreadController popularBreadController;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(popularBreadController).build();
	}

	@Test
	void getPopularBreads_returns200WithDefaultSize() throws Exception {
		given(popularBreadQueryService.getPopularBreads(3)).willReturn(List.of(
			new PopularBreadResponse("소금빵", 120L), new PopularBreadResponse("크루아상", 80L)));

		mockMvc.perform(get("/breads/popular"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data[0].name").value("소금빵"))
			.andExpect(jsonPath("$.data[0].count").value(120))
			.andExpect(jsonPath("$.data[1].name").value("크루아상"));
	}

	@Test
	void getPopularBreads_passesSize() throws Exception {
		given(popularBreadQueryService.getPopularBreads(6)).willReturn(List.of());

		mockMvc.perform(get("/breads/popular").param("size", "6"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data").isArray());
	}
}
