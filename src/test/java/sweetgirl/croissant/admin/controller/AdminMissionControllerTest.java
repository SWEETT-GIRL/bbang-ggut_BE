package sweetgirl.croissant.admin.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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

import sweetgirl.croissant.admin.service.AdminMissionService;
import sweetgirl.croissant.global.advice.GlobalExceptionHandler;

@ExtendWith(MockitoExtension.class)
class AdminMissionControllerTest {

	MockMvc mockMvc;

	@Mock
	AdminMissionService adminMissionService;

	@InjectMocks
	AdminMissionController controller;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(controller)
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	@Test
	void create_returnsCreatedMissionId() throws Exception {
		given(adminMissionService.create(any())).willReturn(4L);

		mockMvc.perform(post("/api/admin/missions")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"code":"record-5","title":"기록 다섯 개","conditionType":"RECORD_COUNT",\
					"threshold":5,"actionDeeplink":"daedongbbangmap://bbanglog/record","sortOrder":4}
					"""))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.data.missionId").value(4));
	}

	@Test
	void create_rejectsMissingDeeplink() throws Exception {
		mockMvc.perform(post("/api/admin/missions")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"code":"record-5","title":"기록 다섯 개",\
					"conditionType":"RECORD_COUNT","threshold":5,"sortOrder":4}
					"""))
			.andExpect(status().isBadRequest());
	}

	@Test
	void updateAndDeactivate_returnOk() throws Exception {
		mockMvc.perform(patch("/api/admin/missions/4")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"title\":\"새 제목\"}"))
			.andExpect(status().isOk());

		mockMvc.perform(delete("/api/admin/missions/4"))
			.andExpect(status().isOk());
	}
}
