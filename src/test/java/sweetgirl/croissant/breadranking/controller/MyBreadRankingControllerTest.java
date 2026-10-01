package sweetgirl.croissant.breadranking.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import sweetgirl.croissant.breadranking.dto.response.BreadCategoryResponse;
import sweetgirl.croissant.breadranking.dto.response.MyBreadCategoryResponse;
import sweetgirl.croissant.breadranking.dto.response.MyBreadRankingChangeResponse;
import sweetgirl.croissant.breadranking.dto.response.MyBreadRankingResponse;
import sweetgirl.croissant.breadranking.dto.response.MyBreadRatingResponse;
import sweetgirl.croissant.breadranking.dto.response.StoreSummaryResponse;
import sweetgirl.croissant.breadranking.enums.RankMode;
import sweetgirl.croissant.breadranking.enums.TextureChip;
import sweetgirl.croissant.breadranking.service.MyBreadRankingCommandService;
import sweetgirl.croissant.breadranking.service.MyBreadRankingQueryService;
import sweetgirl.croissant.global.advice.GlobalExceptionHandler;

@ExtendWith(MockitoExtension.class)
class MyBreadRankingControllerTest {

	private static final BreadCategoryResponse SALT = new BreadCategoryResponse(1L, "소금빵", "소금빵", "빵", true, 0);
	private static final StoreSummaryResponse STORE = new StoreSummaryResponse(10L, "밀도 성수", null, "서울 성동구 성수동");

	private MockMvc mockMvc;

	@Mock
	MyBreadRankingQueryService rankingQueryService;
	@Mock
	MyBreadRankingCommandService rankingCommandService;

	@InjectMocks
	MyBreadRankingController controller;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(controller)
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	@Test
	@DisplayName("GET /bread-rankings/me/categories — 내 탭과 1등 가게")
	void getMyCategories_returns200() throws Exception {
		given(rankingQueryService.getMyCategories(isNull()))
			.willReturn(List.of(new MyBreadCategoryResponse(SALT, 0, List.of(STORE))));

		mockMvc.perform(get("/bread-rankings/me/categories"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data[0].category.name").value("소금빵"))
			.andExpect(jsonPath("$.data[0].topStores[0].storeId").value(10));
	}

	@Test
	@DisplayName("POST /bread-rankings/me/{categoryId}/stores — 담기")
	void addStore_returns200() throws Exception {
		given(rankingCommandService.addStore(isNull(), eq(1L), eq(10L)))
			.willReturn(new MyBreadRankingResponse(SALT, List.of(), List.of()));

		mockMvc.perform(post("/bread-rankings/me/1/stores")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"storeId\":10}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.category.id").value(1));
	}

	@Test
	@DisplayName("PUT /bread-rankings/me/{categoryId}/stores/{storeId}/rank — 등수와 모드")
	void placeStore_returns200() throws Exception {
		given(rankingCommandService.placeStore(isNull(), eq(1L), eq(10L), eq(1), eq(RankMode.TIE)))
			.willReturn(new MyBreadRankingChangeResponse(List.of(), List.of(STORE), true,
				new MyBreadRankingResponse(SALT, List.of(), List.of())));

		mockMvc.perform(put("/bread-rankings/me/1/stores/10/rank")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"rank\":1,\"mode\":\"TIE\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.topChanged").value(true))
			.andExpect(jsonPath("$.data.currentTop[0].storeId").value(10));
	}

	@Test
	@DisplayName("PUT rank — 0등이나 모르는 모드는 400")
	void placeStore_invalidBody_returns400() throws Exception {
		mockMvc.perform(put("/bread-rankings/me/1/stores/10/rank")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"rank\":0,\"mode\":\"TIE\"}"))
			.andExpect(status().isBadRequest());
		mockMvc.perform(put("/bread-rankings/me/1/stores/10/rank")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"rank\":1,\"mode\":\"SWAP\"}"))
			.andExpect(status().isBadRequest());

		verify(rankingCommandService, never()).placeStore(any(), anyLong(), anyLong(), anyInt(), any());
	}

	@Test
	@DisplayName("DELETE /bread-rankings/me/{categoryId}/stores/{storeId} — 빼기")
	void removeStore_returns200() throws Exception {
		given(rankingCommandService.removeStore(isNull(), eq(1L), eq(10L)))
			.willReturn(new MyBreadRankingChangeResponse(List.of(STORE), List.of(), true,
				new MyBreadRankingResponse(SALT, List.of(), List.of())));

		mockMvc.perform(delete("/bread-rankings/me/1/stores/10"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.previousTop[0].storeId").value(10));
	}

	@Test
	@DisplayName("PATCH /bread-rankings/me/{categoryId}/stores/{storeId}/rating — 평가 고치기")
	void updateRating_returns200() throws Exception {
		given(rankingCommandService.updateRating(isNull(), eq(1L), eq(10L), any()))
			.willReturn(new MyBreadRatingResponse(21L, 11L, null, null, null, true, List.of(TextureChip.겉바속촉),
				List.of(), "짭짤해"));

		mockMvc.perform(patch("/bread-rankings/me/1/stores/10/rating")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"again\":true,\"textures\":[\"겉바속촉\"],\"memo\":\"짭짤해\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.again").value(true))
			.andExpect(jsonPath("$.data.textures[0]").value("겉바속촉"));
	}

	@Test
	@DisplayName("GET /bread-rankings/me/{categoryId}/candidates?query= — 후보")
	void getCandidates_returns200() throws Exception {
		given(rankingQueryService.getCandidates(isNull(), eq(1L), eq("밀도"))).willReturn(List.of(STORE));

		mockMvc.perform(get("/bread-rankings/me/1/candidates").param("query", "밀도"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data[0].name").value("밀도 성수"));
	}
}
