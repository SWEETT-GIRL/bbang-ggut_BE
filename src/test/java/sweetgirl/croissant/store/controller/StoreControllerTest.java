package sweetgirl.croissant.store.controller;

import static org.mockito.ArgumentMatchers.eq;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import sweetgirl.croissant.global.response.SliceResponse;
import sweetgirl.croissant.store.dto.response.StoreSearchResponse;
import sweetgirl.croissant.store.service.StoreService;
import sweetgirl.croissant.store.service.search.StoreSearchService;

@ExtendWith(MockitoExtension.class)
class StoreControllerTest {

	private MockMvc mockMvc;

	@Mock
	private StoreService storeService;

	@Mock
	private StoreSearchService storeSearchService;

	@InjectMocks
	private StoreController storeController;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(storeController).build();
	}

	@Test
	void searchStores_returnsOpenSearchResults() throws Exception {
		StoreSearchResponse response = new StoreSearchResponse(
			102L, "성수 베이크", "서울 성동구 성수동", null, "kakao-102", "02-0000", "127.0", "37.5", List.of());
		given(storeSearchService.searchStores(eq("성수 에그타르트"), eq(PageRequest.of(0, 15))))
			.willReturn(new SliceResponse<>(List.of(response), false));

		mockMvc.perform(get("/stores/search")
			.param("keyword", "성수 에그타르트"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.content[0].id").value(102))
			.andExpect(jsonPath("$.data.content[0].name").value("성수 베이크"))
			.andExpect(jsonPath("$.data.hasNext").value(false));
	}
}
