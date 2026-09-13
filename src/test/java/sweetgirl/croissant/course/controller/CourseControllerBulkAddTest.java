package sweetgirl.croissant.course.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

import sweetgirl.croissant.course.dto.response.CourseStoreResponse;
import sweetgirl.croissant.course.service.CourseCommandService;
import sweetgirl.croissant.course.service.CourseMemberService;
import sweetgirl.croissant.course.service.CourseProgressService;
import sweetgirl.croissant.course.service.CourseQueryService;
import sweetgirl.croissant.course.service.CourseStoreVisitService;
import sweetgirl.croissant.course.service.SavedCourseCommandService;
import sweetgirl.croissant.global.advice.GlobalExceptionHandler;

/** bulk 담기 엔드포인트(#660)만 본다. */
@ExtendWith(MockitoExtension.class)
class CourseControllerBulkAddTest {

	private MockMvc mockMvc;

	@Mock
	private CourseQueryService courseQueryService;
	@Mock
	private CourseCommandService courseCommandService;
	@Mock
	private SavedCourseCommandService savedCourseCommandService;
	@Mock
	private CourseProgressService courseProgressService;
	@Mock
	private CourseStoreVisitService courseStoreVisitService;
	@Mock
	private CourseMemberService courseMemberService;

	@InjectMocks
	private CourseController courseController;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(courseController)
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	@Test
	@DisplayName("본문 {recommendationId, stores[{storeId, day}]} 를 받아 담긴 가게 배열을 돌려준다")
	void addStores_returnsCourseStores() throws Exception {
		CourseStoreResponse added = new CourseStoreResponse(1L, 1, 1, 100L, "빵집", 37.5, 127.0, "서울 성동구 성수동",
			null, null, false, null, null, List.of());
		given(courseCommandService.addStores(eq(null), eq(10L), any())).willReturn(List.of(added));

		mockMvc.perform(post("/courses/10/stores/bulk").contentType(MediaType.APPLICATION_JSON)
			.content("{\"recommendationId\": 99, \"stores\": [{\"storeId\": 100, \"day\": 1}]}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data[0].storeId").value(100))
			.andExpect(jsonPath("$.data[0].visitOrder").value(1));
	}

	@Test
	@DisplayName("가게 목록이 비면 400 이고 서비스는 불리지 않는다")
	void addStores_emptyStores_isBadRequest() throws Exception {
		mockMvc.perform(post("/courses/10/stores/bulk").contentType(MediaType.APPLICATION_JSON)
			.content("{\"stores\": []}"))
			.andExpect(status().isBadRequest());

		verify(courseCommandService, never()).addStores(any(), any(), any());
	}
}
