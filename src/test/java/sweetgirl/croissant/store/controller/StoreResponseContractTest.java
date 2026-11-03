package sweetgirl.croissant.store.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import sweetgirl.croissant.global.contract.ResponseContractWebMvcTest;
import sweetgirl.croissant.global.dto.ImageResponse;
import sweetgirl.croissant.global.response.SliceResponse;
import sweetgirl.croissant.profile.service.StoreRecorderQueryService;
import sweetgirl.croissant.store.dto.response.StoreAutocompleteResponse;
import sweetgirl.croissant.store.dto.response.StoreDetailResponse;
import sweetgirl.croissant.store.dto.response.StoreSearchResponse;
import sweetgirl.croissant.store.dto.response.TopSubscribedStoreResponse;
import sweetgirl.croissant.store.enums.AutocompleteSuggestionType;
import sweetgirl.croissant.store.service.StoreCommandService;
import sweetgirl.croissant.store.service.StoreInBoundsQueryService;
import sweetgirl.croissant.store.service.StoreQueryService;
import sweetgirl.croissant.store.service.search.StoreSearchService;

/**
 * 앱(daedongbbangmap)이 실제로 읽는 가게 API 응답 필드를 고정한다. 필드를 지우거나 이름·타입을 바꾸면 이미 설치된 앱이 깨진다
 * (#818 → 1.5.x "가게 고르기" 크래시). 주석의 파일:라인은 앱 레포 기준 사용처다.
 */
@ContextConfiguration(classes = StoreController.class)
class StoreResponseContractTest extends ResponseContractWebMvcTest {

	@MockitoBean
	private StoreQueryService storeQueryService;

	@MockitoBean
	private StoreCommandService storeCommandService;

	@MockitoBean
	private StoreSearchService storeSearchService;

	@MockitoBean
	private StoreInBoundsQueryService storeInBoundsQueryService;

	@MockitoBean
	private StoreRecorderQueryService storeRecorderQueryService;

	@Test
	void popular_keepsFieldsTheAppReads() throws Exception {
		given(storeQueryService.getTopSubscribedStores()).willReturn(List.of(
			new TopSubscribedStoreResponse(1L, "성심당", "대전 중구", "대전 중구 대종로480번길 15", "stores/1/320.jpg", 0L)));

		mockMvc.perform(get("/stores/popular"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data").isArray())
			// StorePickStep.tsx:118,456 — 없으면 가게 없는 기록으로 저장된다
			.andExpect(jsonPath("$.data[0].id").isNumber())
			// StorePickStep.tsx:451, map.tsx:697 — b.name.trim() 이라 없으면 크래시
			.andExpect(jsonPath("$.data[0].name").isString())
			// StorePickStep.tsx:452,456 — 동네 표시
			.andExpect(jsonPath("$.data[0].region").isString())
			// StorePickStep.tsx:452 — #818 에서 지워져 장애가 났던 필드
			.andExpect(jsonPath("$.data[0].address").isString())
			// StorePickStep.tsx:453
			.andExpect(jsonPath("$.data[0].thumbnailImageKey").isString())
			// map.tsx:694 — 구버전 앱이 이 값으로 다시 정렬한다(항상 0)
			.andExpect(jsonPath("$.data[0].subscribeCount").isNumber());
	}

	@Test
	void search_keepsFieldsTheAppReads() throws Exception {
		given(storeSearchService.searchStores(eq("성심당"), isNull(), isNull(), any(), any()))
			.willReturn(new SliceResponse<>(List.of(new StoreSearchResponse(1L, "성심당", "대전 중구 대종로480번길 15",
				"stores/1/320.jpg", "kakao-1", "1588-8069", "127.4273", "36.3276", List.of(), List.of())), true));

		mockMvc.perform(get("/stores/search").param("query", "성심당"))
			.andExpect(status().isOk())
			// useSearch.ts:90,97 — 페이지 넘김
			.andExpect(jsonPath("$.data.content").isArray())
			.andExpect(jsonPath("$.data.hasNext").isBoolean())
			// StorePickStep.tsx:147, map.tsx:335
			.andExpect(jsonPath("$.data.content[0].id").isNumber())
			// StorePickStep.tsx:431, map.tsx:1510 — 없으면 크래시
			.andExpect(jsonPath("$.data.content[0].name").isString())
			// StorePickStep.tsx:432, map.tsx:1513
			.andExpect(jsonPath("$.data.content[0].address").isString())
			// StorePickStep.tsx:433, map.tsx:269
			.andExpect(jsonPath("$.data.content[0].thumbnailImageKey").isString())
			// map.tsx:333-334 — 지도 핀 좌표. 숫자가 아니라 문자열이다
			.andExpect(jsonPath("$.data.content[0].x").isString())
			.andExpect(jsonPath("$.data.content[0].y").isString());
	}

	@Test
	void autocomplete_keepsFieldsTheAppReads() throws Exception {
		given(storeSearchService.autocompleteStores(eq("성수"), anyInt(), isNull())).willReturn(List.of(
			new StoreAutocompleteResponse("성수동", AutocompleteSuggestionType.REGION, 7L, 37.5445, 127.0557, 1500,
				"서울 성동구")));

		mockMvc.perform(get("/stores/autocomplete").param("query", "성수"))
			.andExpect(status().isOk())
			// useStoreAutocomplete.ts:27-28 — startsWith 라 없으면 크래시
			.andExpect(jsonPath("$.data[0].keyword").isString())
			// useStoreAutocomplete.ts:97,100 — 모르는 값이면 결과가 전부 사라진다
			.andExpect(jsonPath("$.data[0].type").value("REGION"))
			// map.tsx:2127
			.andExpect(jsonPath("$.data[0].storeId").isNumber())
			// map.tsx:2139, useStoreAutocomplete.ts:32
			.andExpect(jsonPath("$.data[0].centerLat").isNumber())
			.andExpect(jsonPath("$.data[0].centerLng").isNumber())
			// map.tsx:787-788
			.andExpect(jsonPath("$.data[0].radiusMeters").isNumber())
			// useStoreAutocomplete.ts:32,37, map.tsx:2125
			.andExpect(jsonPath("$.data[0].parentName").isString());
	}

	@Test
	void detail_keepsFieldsTheAppReads() throws Exception {
		given(storeQueryService.getStoreDetail(any(), eq(1L))).willReturn(new StoreDetailResponse(
			1L, "성심당", "대전 중구 대종로480번길 15", 36.3276, 127.4273,
			List.of(new StoreDetailResponse.BusinessHourResponse("MONDAY", LocalTime.of(8, 0), LocalTime.of(22, 0),
				false)),
			"1588-8069",
			List.of(new ImageResponse(11L, "stores/1/a.jpg", "stores/1/640/a.jpg")),
			List.of(new StoreDetailResponse.MenuImageResponse(21L, 31L, "menus/21/a.jpg", "menus/21/640/a.jpg")),
			false, true));

		mockMvc.perform(get("/stores/1"))
			.andExpect(status().isOk())
			// [id].tsx:201-202,282,457
			.andExpect(jsonPath("$.data.id").isNumber())
			.andExpect(jsonPath("$.data.name").isString())
			// [id].tsx:209 — .trim() 이라 없으면 에러 화면
			.andExpect(jsonPath("$.data.address").isString())
			// [id].tsx:563-564, map.tsx:1012-1016
			.andExpect(jsonPath("$.data.latitude").isNumber())
			.andExpect(jsonPath("$.data.longitude").isNumber())
			// [id].tsx:129-133,352-357 — businessHours.find 에 ?. 가 없어 없으면 크래시
			.andExpect(jsonPath("$.data.businessHours[0].dayOfWeek").value("MONDAY"))
			// [id].tsx:122 — "HH:MM:SS" 문자열 앞 5자리를 잘라 쓴다
			.andExpect(jsonPath("$.data.businessHours[0].openTime").value("08:00:00"))
			.andExpect(jsonPath("$.data.businessHours[0].closeTime").value("22:00:00"))
			.andExpect(jsonPath("$.data.businessHours[0].isClosed").value(false))
			// [id].tsx:797-811
			.andExpect(jsonPath("$.data.phone").isString())
			// [id].tsx:204,320, map.tsx:1009
			.andExpect(jsonPath("$.data.images[0].awsKey").isString())
			.andExpect(jsonPath("$.data.images[0].previewImageKey").isString())
			// [id].tsx:211 — menuImages.map 에 ?. 가 없어 없으면 에러 화면
			.andExpect(jsonPath("$.data.menuImages[0].menuId").isNumber())
			.andExpect(jsonPath("$.data.menuImages[0].awsKey").isString())
			.andExpect(jsonPath("$.data.menuImages[0].previewImageKey").isString())
			// [id].tsx:199 — 키 이름이 isSaved 여야 한다(saved 로 바뀌면 저장 상태가 안 보임)
			.andExpect(jsonPath("$.data.isSaved").value(true));
	}
}
