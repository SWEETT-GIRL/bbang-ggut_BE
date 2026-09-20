package sweetgirl.croissant.world.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import sweetgirl.croissant.global.advice.GlobalExceptionHandler;
import sweetgirl.croissant.world.dto.response.WorldCatalogResponse;
import sweetgirl.croissant.world.service.WorldCatalogService;

@ExtendWith(MockitoExtension.class)
class WorldCatalogControllerTest {

	private MockMvc mockMvc;

	@Mock
	private WorldCatalogService worldCatalogService;

	@InjectMocks
	private WorldCatalogController worldCatalogController;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(worldCatalogController)
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
		given(worldCatalogService.getCatalog())
			.willReturn(new WorldCatalogResponse(7, List.of(), List.of()));
	}

	@Test
	@DisplayName("카탈로그를 내리면서 버전으로 만든 ETag 를 함께 준다")
	void returnsCatalogWithETag() throws Exception {
		mockMvc.perform(get("/world/catalog"))
			.andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.ETAG, "W/\"7\""))
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.version").value(7));
	}

	@Test
	@DisplayName("들고 있는 ETag 가 같으면 304 로 답하고 본문을 보내지 않는다")
	void returnsNotModifiedWhenETagMatches() throws Exception {
		mockMvc.perform(get("/world/catalog").header(HttpHeaders.IF_NONE_MATCH, "W/\"7\""))
			.andExpect(status().isNotModified())
			.andExpect(header().string(HttpHeaders.ETAG, "W/\"7\""))
			.andExpect(jsonPath("$.data").doesNotExist());
	}

	@Test
	@DisplayName("버전이 오른 뒤 옛 ETag 로 물으면 새 본문을 내린다")
	void returnsBodyWhenETagIsStale() throws Exception {
		mockMvc.perform(get("/world/catalog").header(HttpHeaders.IF_NONE_MATCH, "W/\"6\""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.version").value(7));
	}

	@Test
	@DisplayName("If-None-Match 에 값이 여럿이어도 그중 하나가 맞으면 304 다")
	void matchesAnyOfSeveralETags() throws Exception {
		mockMvc.perform(get("/world/catalog")
			.header(HttpHeaders.IF_NONE_MATCH, "W/\"5\", W/\"7\""))
			.andExpect(status().isNotModified());
	}
}
