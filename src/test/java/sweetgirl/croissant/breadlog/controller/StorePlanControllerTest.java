package sweetgirl.croissant.breadlog.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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

import sweetgirl.croissant.breadlog.dto.request.StorePlanUpsertRequest;
import sweetgirl.croissant.breadlog.dto.response.StorePlanResponse;
import sweetgirl.croissant.breadlog.service.StorePlanService;
import sweetgirl.croissant.global.advice.GlobalExceptionHandler;

@ExtendWith(MockitoExtension.class)
class StorePlanControllerTest {

	private MockMvc mockMvc;

	@Mock
	StorePlanService storePlanService;

	@InjectMocks
	StorePlanController controller;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(controller)
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	@Test
	void upsert_returns200() throws Exception {
		given(storePlanService.upsert(isNull(), eq(10L), any(StorePlanUpsertRequest.class)))
			.willReturn(new StorePlanResponse(10L, "소금빵 꼭 먹기", null));

		mockMvc.perform(put("/store-plans/10")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"memo\":\"소금빵 꼭 먹기\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.storeId").value(10))
			.andExpect(jsonPath("$.data.memo").value("소금빵 꼭 먹기"));
	}
}
