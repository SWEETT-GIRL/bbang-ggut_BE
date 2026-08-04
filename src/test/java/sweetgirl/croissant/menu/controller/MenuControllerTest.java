package sweetgirl.croissant.menu.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
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
import sweetgirl.croissant.menu.code.MenuErrorCode;
import sweetgirl.croissant.menu.dto.request.MenuCreateRequest;
import sweetgirl.croissant.menu.dto.response.MenuRegistrationResult;
import sweetgirl.croissant.menu.dto.response.StoreMenuResponse;
import sweetgirl.croissant.menu.exception.MenuException;
import sweetgirl.croissant.menu.service.MenuCommandService;
import sweetgirl.croissant.menu.service.MenuQueryService;

@ExtendWith(MockitoExtension.class)
class MenuControllerTest {

	private MockMvc mockMvc;

	@Mock
	private MenuQueryService menuQueryService;

	@Mock
	private MenuCommandService menuCommandService;

	@InjectMocks
	private MenuController menuController;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders
			.standaloneSetup(menuController)
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	@Test
	void getStoreMenus_returnsReviewCount() throws Exception {
		given(menuQueryService.getStoreMenus(eq(1L), isNull()))
			.willReturn(List.of(
				new StoreMenuResponse(1L, "소금빵", "짭짤한 버터 풍미", "빵", null, 3500, 12, false, 8L),
				new StoreMenuResponse(2L, "식빵", null, "빵", null, 5000, 3, false, 0L)));

		mockMvc.perform(get("/stores/1/menus"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data[0].menuId").value(1))
			.andExpect(jsonPath("$.data[0].description").value("짭짤한 버터 풍미"))
			.andExpect(jsonPath("$.data[0].reviewCount").value(8))
			.andExpect(jsonPath("$.data[1].menuId").value(2))
			.andExpect(jsonPath("$.data[1].description").doesNotExist())
			.andExpect(jsonPath("$.data[1].reviewCount").value(0));
	}

	@Test
	void createStoreMenu_returns201ForNewMenu() throws Exception {
		StoreMenuResponse response = new StoreMenuResponse(
			3L, "흑임자 소금빵", null, null, null, null, 0, false, 0L);
		given(menuCommandService.registerMenu(eq(1L), isNull(), any(MenuCreateRequest.class)))
			.willReturn(new MenuRegistrationResult(3L, true));
		given(menuQueryService.getStoreMenu(3L, null)).willReturn(response);

		mockMvc.perform(post("/stores/1/menus")
			.contentType("application/json")
			.content("{\"name\":\"흑임자 소금빵\"}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.data.menuId").value(3))
			.andExpect(jsonPath("$.data.name").value("흑임자 소금빵"))
			.andExpect(jsonPath("$.data.category").doesNotExist());
	}

	@Test
	void createStoreMenu_returns200ForDuplicateMenu() throws Exception {
		StoreMenuResponse response = new StoreMenuResponse(
			3L, "흑임자소금빵", null, "빵", null, null, 0, false, 0L);
		given(menuCommandService.registerMenu(eq(1L), isNull(), any(MenuCreateRequest.class)))
			.willReturn(new MenuRegistrationResult(3L, false));
		given(menuQueryService.getStoreMenu(3L, null)).willReturn(response);

		mockMvc.perform(post("/stores/1/menus")
			.contentType("application/json")
			.content("{\"name\":\"흑임자 소금빵\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.menuId").value(3));
	}

	@Test
	void likeMenu_returns200() throws Exception {
		mockMvc.perform(post("/menus/1/like"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true));
	}

	@Test
	void likeMenu_returns404_whenMenuNotFound() throws Exception {
		willThrow(new MenuException(MenuErrorCode.MENU_NOT_FOUND))
			.given(menuCommandService).likeMenu(eq(99L), isNull());

		mockMvc.perform(post("/menus/99/like"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.code").value("MENU404"));
	}

	@Test
	void likeMenu_returns409_whenAlreadyLiked() throws Exception {
		willThrow(new MenuException(MenuErrorCode.ALREADY_LIKED))
			.given(menuCommandService).likeMenu(eq(1L), isNull());

		mockMvc.perform(post("/menus/1/like"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.code").value("MENU409"));
	}

	@Test
	void cancelMenuLike_returns200() throws Exception {
		mockMvc.perform(delete("/menus/1/like"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true));
	}

	@Test
	void cancelMenuLike_returns404_whenNotLiked() throws Exception {
		willThrow(new MenuException(MenuErrorCode.NOT_LIKED))
			.given(menuCommandService).cancelMenuLike(eq(1L), isNull());

		mockMvc.perform(delete("/menus/1/like"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.code").value("MENU4042"));
	}
}
