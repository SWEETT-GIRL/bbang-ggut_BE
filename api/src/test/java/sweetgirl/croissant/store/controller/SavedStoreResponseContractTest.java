package sweetgirl.croissant.store.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import sweetgirl.croissant.global.contract.ResponseContractWebMvcTest;
import sweetgirl.croissant.store.dto.response.SavedStoreResponse;
import sweetgirl.croissant.store.service.SavedStoreCommandService;
import sweetgirl.croissant.store.service.SavedStoreQueryService;

/**
 * 앱이 실제로 읽는 "담아둔 곳" 응답 필드를 고정한다. 빵로그 "가게 고르기" 화면과 지도 핀이 쓴다. 주석의 파일:라인은 앱 레포 기준 사용처다.
 */
@ContextConfiguration(classes = SavedStoreController.class)
class SavedStoreResponseContractTest extends ResponseContractWebMvcTest {

	@MockitoBean
	private SavedStoreQueryService savedStoreQueryService;

	@MockitoBean
	private SavedStoreCommandService savedStoreCommandService;

	@Test
	void saved_keepsFieldsTheAppReads() throws Exception {
		given(savedStoreQueryService.getSavedStores(any())).willReturn(List.of(new SavedStoreResponse(
			1L, "성심당", "대전 중구", "대전 중구 대종로480번길 15", 36.3276, 127.4273, "stores/1/320.jpg")));

		mockMvc.perform(get("/stores/saved"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data").isArray())
			// StorePickStep.tsx:68,416
			.andExpect(jsonPath("$.data[0].storeId").isNumber())
			// StorePickStep.tsx:69 — storeName.includes 라 없으면 크래시
			.andExpect(jsonPath("$.data[0].storeName").isString())
			// StorePickStep.tsx:411
			.andExpect(jsonPath("$.data[0].region").isString())
			.andExpect(jsonPath("$.data[0].address").isString())
			// StorePickStep.tsx:412
			.andExpect(jsonPath("$.data[0].thumbnailImageKey").isString())
			// map.tsx:314 — 없으면 지도에서 담아둔 곳 핀이 빠진다
			.andExpect(jsonPath("$.data[0].latitude").isNumber())
			.andExpect(jsonPath("$.data[0].longitude").isNumber());
	}
}
