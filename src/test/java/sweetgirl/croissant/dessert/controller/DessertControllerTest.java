package sweetgirl.croissant.dessert.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
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

import sweetgirl.croissant.dessert.code.DessertErrorCode;
import sweetgirl.croissant.dessert.dto.response.DessertStoreResponse;
import sweetgirl.croissant.dessert.dto.response.HotDessertResponse;
import sweetgirl.croissant.dessert.enums.DessertGrade;
import sweetgirl.croissant.dessert.enums.DessertItemType;
import sweetgirl.croissant.dessert.enums.DessertTrendStatus;
import sweetgirl.croissant.dessert.exception.DessertException;
import sweetgirl.croissant.dessert.service.DessertQueryService;
import sweetgirl.croissant.global.advice.GlobalExceptionHandler;

@ExtendWith(MockitoExtension.class)
class DessertControllerTest {

	private MockMvc mockMvc;

	@Mock
	private DessertQueryService dessertQueryService;

	@InjectMocks
	private DessertController dessertController;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders
			.standaloneSetup(dessertController)
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	@Test
	@DisplayName("칩 줄은 이름과 판정을 함께 준다")
	void getHotDesserts_returnsChips() throws Exception {
		given(dessertQueryService.getHotDesserts()).willReturn(List.of(
			new HotDessertResponse(1L, "피자설기", DessertItemType.GENERIC, null, DessertTrendStatus.HOT)));

		mockMvc.perform(get("/desserts"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data[0].name").value("피자설기"))
			.andExpect(jsonPath("$.data[0].itemType").value("GENERIC"))
			.andExpect(jsonPath("$.data[0].trendStatus").value("HOT"));
	}

	@Test
	@DisplayName("판매처는 좌표와 근거를 함께 준다")
	void getSellingStores_returnsStores() throws Exception {
		given(dessertQueryService.getSellingStores(eq(1L), eq(37.5665), eq(126.978), eq(30), isNull()))
			.willReturn(List.of(new DessertStoreResponse(
				9963L, "원조공주떡집", "서울 중구 한강대로 416", "resized-320",
				37.5555, 126.9737, 1278, DessertGrade.STRONG, 2, LocalDate.of(2026, 9, 17), null)));

		mockMvc.perform(get("/desserts/1/stores")
			.param("lat", "37.5665")
			.param("lng", "126.978"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data[0].storeId").value(9963))
			.andExpect(jsonPath("$.data[0].distanceMeters").value(1278))
			.andExpect(jsonPath("$.data[0].grade").value("STRONG"))
			.andExpect(jsonPath("$.data[0].lastEvidenceAt").value("2026-09-17"));
	}

	@Test
	@DisplayName("없는 디저트를 물으면 404")
	void getSellingStores_returns404WhenDessertMissing() throws Exception {
		willThrow(new DessertException(DessertErrorCode.DESSERT_NOT_FOUND))
			.given(dessertQueryService)
			.getSellingStores(eq(404L), eq(37.5665), eq(126.978), eq(30), isNull());

		mockMvc.perform(get("/desserts/404/stores")
			.param("lat", "37.5665")
			.param("lng", "126.978"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("DESSERT404"));
	}
}
