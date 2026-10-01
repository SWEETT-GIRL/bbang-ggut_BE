package sweetgirl.croissant.store.controller;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

import sweetgirl.croissant.global.advice.GlobalExceptionHandler;
import sweetgirl.croissant.store.code.SavedStoreErrorCode;
import sweetgirl.croissant.store.dto.response.SavedStoreResponse;
import sweetgirl.croissant.store.exception.SavedStoreException;
import sweetgirl.croissant.store.service.SavedStoreCommandService;
import sweetgirl.croissant.store.service.SavedStoreQueryService;

@ExtendWith(MockitoExtension.class)
class SavedStoreControllerTest {

	private MockMvc mockMvc;

	@Mock
	private SavedStoreQueryService savedStoreQueryService;
	@Mock
	private SavedStoreCommandService savedStoreCommandService;

	@InjectMocks
	private SavedStoreController savedStoreController;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders
			.standaloneSetup(savedStoreController)
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	@Test
	void getSavedStores_returns200() throws Exception {
		SavedStoreResponse response = new SavedStoreResponse(10L, "소금빵 가게", "마포구", "서울 마포구 월드컵로 1", 37.55, 126.92,
			"resized/320/store10.jpg");
		given(savedStoreQueryService.getSavedStores(isNull())).willReturn(List.of(response));

		mockMvc.perform(get("/stores/saved"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data[0].storeId").value(10))
			.andExpect(jsonPath("$.data[0].storeName").value("소금빵 가게"))
			.andExpect(jsonPath("$.data[0].region").value("마포구"))
			.andExpect(jsonPath("$.data[0].thumbnailImageKey").value("resized/320/store10.jpg"));
	}

	@Test
	void saveStore_returns200() throws Exception {
		willDoNothing().given(savedStoreCommandService).saveStore(isNull(), anyLong());

		mockMvc.perform(post("/stores/10/save"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true));
	}

	@Test
	void unsaveStore_returns200() throws Exception {
		willDoNothing().given(savedStoreCommandService).unsaveStore(isNull(), anyLong());

		mockMvc.perform(delete("/stores/10/save"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true));
	}

	@Test
	void unsaveStore_notFound_returns404() throws Exception {
		willThrow(new SavedStoreException(SavedStoreErrorCode.SAVED_STORE_NOT_FOUND))
			.given(savedStoreCommandService).unsaveStore(isNull(), anyLong());

		mockMvc.perform(delete("/stores/10/save"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.code").value("SAVED_STORE404_1"));
	}
}
