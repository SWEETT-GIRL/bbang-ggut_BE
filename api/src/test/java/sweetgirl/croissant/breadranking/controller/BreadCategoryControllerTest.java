package sweetgirl.croissant.breadranking.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

import sweetgirl.croissant.breadranking.dto.response.BreadCategoryCreateResult;
import sweetgirl.croissant.breadranking.dto.response.BreadCategoryResponse;
import sweetgirl.croissant.breadranking.service.BreadCategoryCommandService;
import sweetgirl.croissant.breadranking.service.BreadCategoryQueryService;
import sweetgirl.croissant.global.advice.GlobalExceptionHandler;

@ExtendWith(MockitoExtension.class)
class BreadCategoryControllerTest {

	private static final BreadCategoryResponse SALT = new BreadCategoryResponse(1L, "소금빵", "소금빵", "빵", true, 48);

	private MockMvc mockMvc;

	@Mock
	BreadCategoryQueryService categoryQueryService;
	@Mock
	BreadCategoryCommandService categoryCommandService;

	@InjectMocks
	BreadCategoryController controller;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(controller)
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	@Test
	@DisplayName("GET /bread-categories — 공용 목록")
	void getCategories_returns200() throws Exception {
		given(categoryQueryService.getCategories(null)).willReturn(List.of(SALT));

		mockMvc.perform(get("/bread-categories"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data[0].name").value("소금빵"))
			.andExpect(jsonPath("$.data[0].voters").value(48))
			.andExpect(jsonPath("$.data[0].isDefault").value(true));
	}

	@Test
	@DisplayName("POST /bread-categories — 새로 만들면 201")
	void create_returns201() throws Exception {
		given(categoryCommandService.create(isNull(), eq("트러플 소금빵")))
			.willReturn(new BreadCategoryCreateResult(
				new BreadCategoryResponse(200L, "트러플 소금빵", null, "빵", false, 0), true));

		mockMvc.perform(post("/bread-categories")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"트러플 소금빵\"}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.data.id").value(200));
	}

	@Test
	@DisplayName("POST /bread-categories — 이미 있으면 200 으로 그걸 준다")
	void create_existing_returns200() throws Exception {
		given(categoryCommandService.create(isNull(), eq("소금빵")))
			.willReturn(new BreadCategoryCreateResult(SALT, false));

		mockMvc.perform(post("/bread-categories")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"소금빵\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.id").value(1));
	}

	@Test
	@DisplayName("POST /bread-categories — 이름이 비면 400")
	void create_blankName_returns400() throws Exception {
		mockMvc.perform(post("/bread-categories")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"  \"}"))
			.andExpect(status().isBadRequest());

		verify(categoryCommandService, never()).create(any(), any());
	}
}
