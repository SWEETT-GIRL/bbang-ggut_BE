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

import sweetgirl.croissant.common.util.MapBounds;
import sweetgirl.croissant.follow.dto.response.FollowMemberResponse;
import sweetgirl.croissant.global.advice.GlobalExceptionHandler;
import sweetgirl.croissant.global.response.SliceResponse;
import sweetgirl.croissant.profile.service.StoreRecorderQueryService;
import sweetgirl.croissant.store.dto.response.StoreAutocompleteResponse;
import sweetgirl.croissant.store.dto.response.StoreSearchResponse;
import sweetgirl.croissant.store.dto.response.StoresInBoundsResponse;
import sweetgirl.croissant.store.dto.response.TopSubscribedStoreResponse;
import sweetgirl.croissant.store.enums.AutocompleteSuggestionType;
import sweetgirl.croissant.store.service.StoreCommandService;
import sweetgirl.croissant.store.service.StoreInBoundsQueryService;
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

	@Mock
	private StoreInBoundsQueryService storeInBoundsQueryService;

	@Mock
	private StoreRecorderQueryService storeRecorderQueryService;

	@InjectMocks
	private StoreController storeController;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(storeController)
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	@Test
	void getStoresInBounds_returnsStoresAndTotal() throws Exception {
		StoreSearchResponse response = new StoreSearchResponse(
			102L, "성수 베이크", "서울 성동구 성수동", "resized/320/a.jpg", "kakao-102", null, "127.05", "37.54", List.of(),
			List.of());
		given(storeInBoundsQueryService.getStoresInBounds(eq(new MapBounds(37.53, 127.03, 37.56, 127.07)),
			eq(37.54), eq(127.05), isNull()))
			.willReturn(new StoresInBoundsResponse(List.of(response), 412));

		mockMvc.perform(get("/stores/in-bounds")
			.param("swLat", "37.53").param("swLng", "127.03")
			.param("neLat", "37.56").param("neLng", "127.07")
			.param("lat", "37.54").param("lng", "127.05"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.content[0].id").value(102))
			.andExpect(jsonPath("$.data.content[0].x").value("127.05"))
			.andExpect(jsonPath("$.data.total").value(412));
	}

	@Test
	void getRecorders_returnsPeopleWhoRecordedAtStore() throws Exception {
		given(storeRecorderQueryService.recorders(isNull(), eq(10L), eq(3)))
			.willReturn(List.of(new FollowMemberResponse(21L, "빵순이", false, false, null, null)));

		mockMvc.perform(get("/stores/10/recorders").param("limit", "3"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data[0].memberId").value(21))
			.andExpect(jsonPath("$.data[0].nickname").value("빵순이"));
	}

	@Test
	void getNearbyStore_passesRadiusToService() throws Exception {
		given(storeQueryService.getNearbyStores(eq(127.05), eq(37.54), eq(5000), any()))
			.willReturn(new SliceResponse<>(List.of(), false));

		mockMvc.perform(get("/stores/nearby")
			.param("lat", "37.54").param("lng", "127.05").param("radius", "5000"))
			.andExpect(status().isOk());

		verify(storeQueryService).getNearbyStores(eq(127.05), eq(37.54), eq(5000), any());
	}

	@Test
	void getNearbyStore_withoutRadius_passesNull() throws Exception {
		given(storeQueryService.getNearbyStores(eq(127.05), eq(37.54), isNull(), any()))
			.willReturn(new SliceResponse<>(List.of(), false));

		mockMvc.perform(get("/stores/nearby").param("lat", "37.54").param("lng", "127.05"))
			.andExpect(status().isOk());
	}

	@Test
	void getStoresInBounds_rejectsInvertedBounds() throws Exception {
		mockMvc.perform(get("/stores/in-bounds")
			.param("swLat", "37.56").param("swLng", "127.03")
			.param("neLat", "37.53").param("neLng", "127.07"))
			.andExpect(status().isBadRequest());
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

	@Test
	void getTopSubscribedStores_returnsRegionAddressThumbnailAndZeroSubscribeCount() throws Exception {
		given(storeQueryService.getTopSubscribedStores())
			.willReturn(List.of(new TopSubscribedStoreResponse(30L, "성수 베이크", "서울 성동구", "서울 성동구 성수동 1-1",
				"store/30_320.jpg", 0L)));

		mockMvc.perform(get("/stores/popular"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data[0].id").value(30))
			.andExpect(jsonPath("$.data[0].name").value("성수 베이크"))
			.andExpect(jsonPath("$.data[0].region").value("서울 성동구"))
			.andExpect(jsonPath("$.data[0].address").value("서울 성동구 성수동 1-1"))
			.andExpect(jsonPath("$.data[0].thumbnailImageKey").value("store/30_320.jpg"))
			.andExpect(jsonPath("$.data[0].subscribeCount").value(0))
			.andExpect(jsonPath("$.data[0].distanceMeters").doesNotExist());
	}

	@Test
	void getTopSubscribedStores_acceptsLegacyLocationParams() throws Exception {
		given(storeQueryService.getTopSubscribedStores())
			.willReturn(List.of(new TopSubscribedStoreResponse(30L, "성수 베이크", "", null, null, 0L)));

		mockMvc.perform(get("/stores/popular")
			.param("lat", "37.54").param("lng", "127.05"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data[0].id").value(30));
	}
}
