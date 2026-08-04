package sweetgirl.croissant.course.controller;

import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import sweetgirl.croissant.course.dto.response.CourseProgressResponse;
import sweetgirl.croissant.course.enums.CourseProgressStatus;
import sweetgirl.croissant.course.service.CourseProgressService;

@ExtendWith(MockitoExtension.class)
class MemberCourseProgressControllerTest {

	MockMvc mockMvc;

	@Mock
	CourseProgressService courseProgressService;

	@InjectMocks
	MemberCourseProgressController controller;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
	}

	@Test
	void getFinishedProgresses_returnsPermanentHistory() throws Exception {
		LocalDateTime finishedAt = LocalDateTime.of(2026, 8, 4, 12, 0);
		given(
			courseProgressService.getByStatus(isNull(), org.mockito.ArgumentMatchers.eq(CourseProgressStatus.FINISHED)))
			.willReturn(List.of(new CourseProgressResponse(
				1L, "망원 투어", null, null, 3, 3, 100,
				LocalDateTime.of(2026, 8, 1, 12, 0), finishedAt,
				CourseProgressStatus.FINISHED, finishedAt)));

		mockMvc.perform(get("/members/me/course-progresses").param("status", "FINISHED"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data[0].status").value("FINISHED"))
			.andExpect(jsonPath("$.data[0].finishedAt").exists());
	}
}
