package sweetgirl.croissant.menu.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import sweetgirl.croissant.menu.exception.MenuException;
import sweetgirl.croissant.menu.service.MenuService;

@ExtendWith(MockitoExtension.class)
class MenuControllerTest {

	private MockMvc mockMvc;

	@Mock
	private MenuService menuService;

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
	void likeMenu_returns200() throws Exception {
		mockMvc.perform(post("/menus/1/like"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true));
	}

	@Test
	void likeMenu_returns404_whenMenuNotFound() throws Exception {
		willThrow(new MenuException(MenuErrorCode.MENU_NOT_FOUND))
			.given(menuService).likeMenu(eq(99L), isNull());

		mockMvc.perform(post("/menus/99/like"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.code").value("MENU404"));
	}

	@Test
	void likeMenu_returns409_whenAlreadyLiked() throws Exception {
		willThrow(new MenuException(MenuErrorCode.ALREADY_LIKED))
			.given(menuService).likeMenu(eq(1L), isNull());

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
			.given(menuService).cancelMenuLike(eq(1L), isNull());

		mockMvc.perform(delete("/menus/1/like"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.code").value("MENU4042"));
	}
}
