package sweetgirl.croissant.admin.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import sweetgirl.croissant.admin.dto.response.AdminMenuClassificationConfirmResponse;
import sweetgirl.croissant.admin.service.AdminMenuClassificationCommandService;
import sweetgirl.croissant.global.advice.GlobalExceptionHandler;

@ExtendWith(MockitoExtension.class)
class AdminMenuClassificationControllerTest {

	MockMvc mockMvc;

	@Mock
	AdminMenuClassificationCommandService service;

	@InjectMocks
	AdminMenuClassificationController controller;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(controller)
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	@Test
	void confirm_returnsApplied() throws Exception {
		given(service.confirm(any()))
			.willReturn(new AdminMenuClassificationConfirmResponse("슈크림빵", "크림빵", true, 1));

		mockMvc.perform(post("/api/admin/menu-classifications")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"슈크림빵\",\"kind\":\"크림빵\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.normalizedName").value("슈크림빵"))
			.andExpect(jsonPath("$.data.kind").value("크림빵"))
			.andExpect(jsonPath("$.data.appliedMenus").value(1));
	}

	@Test
	void confirm_returns400_whenNameBlank() throws Exception {
		mockMvc.perform(post("/api/admin/menu-classifications")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"\",\"kind\":\"크림빵\"}"))
			.andExpect(status().isBadRequest());
	}
}
