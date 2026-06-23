package sweetgirl.croissant.course.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.ObjectMapper;

import sweetgirl.croissant.course.code.CourseErrorCode;
import sweetgirl.croissant.course.dto.response.CourseDetailResponse;
import sweetgirl.croissant.course.dto.response.CourseSummaryResponse;
import sweetgirl.croissant.course.enums.CourseType;
import sweetgirl.croissant.course.exception.CourseException;
import sweetgirl.croissant.course.service.CourseCommandService;
import sweetgirl.croissant.course.service.CourseQueryService;
import sweetgirl.croissant.course.service.SavedCourseCommandService;
import sweetgirl.croissant.global.advice.GlobalExceptionHandler;

@ExtendWith(MockitoExtension.class)
class CourseControllerTest {

	private MockMvc mockMvc;
	private final ObjectMapper objectMapper = new ObjectMapper();

	@Mock
	private CourseQueryService courseQueryService;
	@Mock
	private CourseCommandService courseCommandService;
	@Mock
	private SavedCourseCommandService savedCourseCommandService;

	@InjectMocks
	private CourseController courseController;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders
			.standaloneSetup(courseController)
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	@Test
	void getCourses_returns200() throws Exception {
		CourseSummaryResponse response = new CourseSummaryResponse(
			1L, "소금빵 투어", null, 2, List.of("마포구"), CourseType.CURATED, "어드민", null);
		given(courseQueryService.getCurationCourses()).willReturn(List.of(response));

		mockMvc.perform(get("/courses"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data[0].id").value(1))
			.andExpect(jsonPath("$.data[0].title").value("소금빵 투어"))
			.andExpect(jsonPath("$.data[0].courseType").value("CURATED"));
	}

	@Test
	void getMyCourses_returns200() throws Exception {
		CourseSummaryResponse response = new CourseSummaryResponse(
			2L, "나의 코스", null, 1, List.of("서대문구"), CourseType.CUSTOM, "user1", null);
		given(courseQueryService.getMyCourses(isNull())).willReturn(List.of(response));

		mockMvc.perform(get("/courses/my"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data[0].id").value(2))
			.andExpect(jsonPath("$.data[0].courseType").value("CUSTOM"));
	}

	@Test
	void getSavedCourses_returns200() throws Exception {
		CourseSummaryResponse response = new CourseSummaryResponse(
			3L, "찜한 코스", null, 3, List.of("강남구"), CourseType.CURATED, "어드민", null);
		given(courseQueryService.getSavedCourses(isNull())).willReturn(List.of(response));

		mockMvc.perform(get("/courses/saved"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data[0].id").value(3));
	}

	@Test
	void getCourseDetail_returns200() throws Exception {
		CourseDetailResponse response = new CourseDetailResponse(
			1L, "소금빵 투어", null, 2, List.of("마포구"), CourseType.CURATED, "어드민", null, false, 5L, List.of());
		given(courseQueryService.getCourseDetail(anyLong(), isNull())).willReturn(response);

		mockMvc.perform(get("/courses/1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.id").value(1))
			.andExpect(jsonPath("$.data.savedCount").value(5))
			.andExpect(jsonPath("$.data.isSaved").value(false));
	}

	@Test
	void getCourseDetail_forbidden_returns403() throws Exception {
		given(courseQueryService.getCourseDetail(anyLong(), isNull()))
			.willThrow(new CourseException(CourseErrorCode.COURSE_FORBIDDEN));

		mockMvc.perform(get("/courses/1"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.code").value("COURSE403_1"));
	}

	@Test
	void getCourseDetail_notFound_returns404() throws Exception {
		given(courseQueryService.getCourseDetail(anyLong(), isNull()))
			.willThrow(new CourseException(CourseErrorCode.COURSE_NOT_FOUND));

		mockMvc.perform(get("/courses/1"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.code").value("COURSE404_1"));
	}

	@Test
	void createCourse_returns201() throws Exception {
		given(courseCommandService.createCourse(isNull(), any())).willReturn(10L);

		mockMvc.perform(post("/courses")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"title\":\"소금빵 투어\",\"isPublic\":true}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.courseId").value(10));
	}

	@Test
	void updateCourse_returns200() throws Exception {
		willDoNothing().given(courseCommandService).updateCourse(isNull(), anyLong(), any());

		mockMvc.perform(patch("/courses/1")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"title\":\"수정된 코스\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true));
	}

	@Test
	void updateCourse_forbidden_returns403() throws Exception {
		willThrow(new CourseException(CourseErrorCode.COURSE_FORBIDDEN))
			.given(courseCommandService).updateCourse(isNull(), anyLong(), any());

		mockMvc.perform(patch("/courses/1")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"title\":\"수정된 코스\"}"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.code").value("COURSE403_1"));
	}

	@Test
	void deleteCourse_returns200() throws Exception {
		willDoNothing().given(courseCommandService).deleteCourse(isNull(), anyLong());

		mockMvc.perform(delete("/courses/1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true));
	}
}
