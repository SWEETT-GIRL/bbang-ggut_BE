package sweetgirl.croissant.admin.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import sweetgirl.croissant.admin.dto.response.AdminBreadTagReclassifyResponse;
import sweetgirl.croissant.admin.service.AdminBreadTagCommandService;
import sweetgirl.croissant.global.advice.GlobalExceptionHandler;

@ExtendWith(MockitoExtension.class)
class AdminBreadTagControllerTest {

	MockMvc mockMvc;

	@Mock
	AdminBreadTagCommandService adminBreadTagCommandService;

	@InjectMocks
	AdminBreadTagController controller;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(controller)
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	@Test
	void reclassifyAll_returnsSummary() throws Exception {
		given(adminBreadTagCommandService.reclassifyAll())
			.willReturn(new AdminBreadTagReclassifyResponse(995, 120, Map.of("빵 → 소금빵", 7L)));

		mockMvc.perform(post("/api/admin/bread-record-tags/reclassify"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.total").value(995))
			.andExpect(jsonPath("$.data.changed").value(120))
			.andExpect(jsonPath("$.data.moves['빵 → 소금빵']").value(7));
	}
}
