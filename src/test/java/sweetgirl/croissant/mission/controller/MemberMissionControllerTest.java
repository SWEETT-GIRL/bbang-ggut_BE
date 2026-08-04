package sweetgirl.croissant.mission.controller;

import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

import sweetgirl.croissant.mission.dto.response.MemberMissionResponse;
import sweetgirl.croissant.mission.dto.response.MissionProgressResponse;
import sweetgirl.croissant.mission.service.MemberMissionService;

@ExtendWith(MockitoExtension.class)
class MemberMissionControllerTest {

	MockMvc mockMvc;

	@Mock
	MemberMissionService memberMissionService;

	@InjectMocks
	MemberMissionController controller;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
	}

	@Test
	void getMissions_returnsProgressContract() throws Exception {
		given(memberMissionService.getMissions(isNull())).willReturn(List.of(
			new MemberMissionResponse(
				"record-5", "기록 다섯 개", "daedongbbangmap://bbanglog/record",
				new MissionProgressResponse(2, 5), false, null)));

		mockMvc.perform(get("/members/me/missions"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data[0].progress.current").value(2))
			.andExpect(jsonPath("$.data[0].progress.target").value(5))
			.andExpect(jsonPath("$.data[0].done").value(false));
	}
}
