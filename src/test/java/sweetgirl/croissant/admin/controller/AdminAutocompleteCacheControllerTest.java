package sweetgirl.croissant.admin.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import sweetgirl.croissant.store.service.search.cache.AutocompleteCacheInvalidator;

@ExtendWith(MockitoExtension.class)
class AdminAutocompleteCacheControllerTest {

	MockMvc mockMvc;

	@Mock
	AutocompleteCacheInvalidator autocompleteCacheInvalidator;

	@InjectMocks
	AdminAutocompleteCacheController controller;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(controller)
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	@Test
	void evictAll_returnsEvictedKeyCount() throws Exception {
		given(autocompleteCacheInvalidator.evictAll()).willReturn(1_234L);

		mockMvc.perform(delete("/admin/search/autocomplete-cache"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.evictedKeyCount").value(1_234));
	}

	@Test
	void evictAll_returnsZeroWhenCacheIsOff() throws Exception {
		// 캐시가 꺼진 환경(로컬·Redis 없음)에서도 실패가 아니라 0 건이다
		given(autocompleteCacheInvalidator.evictAll()).willReturn(0L);

		mockMvc.perform(delete("/admin/search/autocomplete-cache"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.evictedKeyCount").value(0));
	}
}
