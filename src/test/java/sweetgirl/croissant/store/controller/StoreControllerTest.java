package sweetgirl.croissant.store.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

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
import sweetgirl.croissant.store.dto.response.StoreAutocompleteResponse;
import sweetgirl.croissant.store.dto.response.StoreSearchResponse;
import sweetgirl.croissant.store.enums.AutocompleteSuggestionType;
import sweetgirl.croissant.store.service.StoreCommandService;
import sweetgirl.croissant.store.service.StoreQueryService;
import sweetgirl.croissant.store.service.search.SearchSort;
import sweetgirl.croissant.store.service.search.StoreSearchService;

@ExtendWith(MockitoExtension.class)
class StoreControllerTest {

	private MockMvc mockMvc;

	@Mock
	private StoreQueryService storeQueryService;

	@Mock
	private StoreCommandService storeCommandService;

	@Mock
	private StoreSearchService storeSearchService;

	@InjectMocks
	private StoreController storeController;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(storeController).build();
	}

	@Test
	void searchStores_returnsSearchResults() throws Exception {
		StoreSearchResponse response = new StoreSearchResponse(
			102L, "성수 베이크", "서울 성동구 성수동", null, "kakao-102", "02-0000", "127.0", "37.5", List.of(), List.of());
		given(storeSearchService.searchStores(eq("성수 에그타르트"), isNull(), isNull(), eq(SearchSort.RELEVANCE),
			eq(PageRequest.of(0, 15))))
			.willReturn(new SliceResponse<>(List.of(response), false));

		mockMvc.perform(get("/stores/search")
			.param("query", "성수 에그타르트"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.content[0].id").value(102))
			.andExpect(jsonPath("$.data.content[0].name").value("성수 베이크"))
			.andExpect(jsonPath("$.data.hasNext").value(false));
	}

	@Test
	void searchStores_acceptsPopularitySort() throws Exception {
		given(storeSearchService.searchStores(eq("에그타르트"), isNull(), isNull(), eq(SearchSort.POPULARITY),
			eq(PageRequest.of(0, 15))))
			.willReturn(new SliceResponse<>(List.of(), false));

		mockMvc.perform(get("/stores/search")
			.param("query", "에그타르트")
			.param("sort", "popularity"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true));
	}

	@Test
	void autocompleteStores_returnsSuggestions() throws Exception {
		given(storeSearchService.autocompleteStores("소금", 10, null))
			.willReturn(List.of(new StoreAutocompleteResponse("소금빵", AutocompleteSuggestionType.BREAD_TYPE, null)));

		mockMvc.perform(get("/stores/autocomplete")
			.param("query", "소금"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data[0].keyword").value("소금빵"))
			.andExpect(jsonPath("$.data[0].type").value("BREAD_TYPE"));
	}

	@Test
	void autocompleteStores_passesTypesFilterToService() throws Exception {
		given(storeSearchService.autocompleteStores("소금", 10,
			List.of(AutocompleteSuggestionType.STORE, AutocompleteSuggestionType.REGION)))
			.willReturn(List.of(new StoreAutocompleteResponse("소금집델리", AutocompleteSuggestionType.STORE, 1L)));

		mockMvc.perform(get("/stores/autocomplete")
			.param("query", "소금")
			.param("types", "STORE,REGION"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data[0].keyword").value("소금집델리"))
			.andExpect(jsonPath("$.data[0].type").value("STORE"));
	}
}
