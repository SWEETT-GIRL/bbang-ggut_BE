package sweetgirl.croissant.admin.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.time.LocalTime;
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

import sweetgirl.croissant.admin.service.AdminCourseCommandService;
import sweetgirl.croissant.admin.service.AdminCourseQueryService;
import sweetgirl.croissant.course.code.CourseErrorCode;
import sweetgirl.croissant.course.dto.response.AdminCourseDetailResponse;
import sweetgirl.croissant.course.dto.response.AdminCourseResponse;
import sweetgirl.croissant.course.dto.response.CourseStoreResponse;
import sweetgirl.croissant.course.exception.CourseException;
import sweetgirl.croissant.external.aws.dto.PresignedUrlResponse;
import sweetgirl.croissant.global.advice.GlobalExceptionHandler;

@ExtendWith(MockitoExtension.class)
class AdminCourseControllerTest {

	private MockMvc mockMvc;

	@Mock
	private AdminCourseQueryService adminCourseQueryService;

	@Mock
	private AdminCourseCommandService adminCourseCommandService;

	@InjectMocks
	private AdminCourseController adminCourseController;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders
			.standaloneSetup(adminCourseController)
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	// ───────────────────────── 조회 ─────────────────────────

	@Test
	void getCourses_returnsListWithSuccess() throws Exception {
		AdminCourseResponse course = new AdminCourseResponse(
			1L, "망원 빵지순례", "HOT", "대학가", "홍대", "courses/1/cover.jpg", null, null, 3, 10L,
			LocalDateTime.of(2026, 1, 1, 0, 0));
		given(adminCourseQueryService.getCuratedCourses()).willReturn(List.of(course));

		mockMvc.perform(get("/api/admin/courses"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data[0].id").value(1))
			.andExpect(jsonPath("$.data[0].title").value("망원 빵지순례"))
			.andExpect(jsonPath("$.data[0].badge").value("HOT"))
			.andExpect(jsonPath("$.data[0].storeCount").value(3))
			.andExpect(jsonPath("$.data[0].savedCount").value(10))
			.andExpect(jsonPath("$.data[0].category").value("홍대"));
	}

	@Test
	void getCourseDetail_returnsDetailWithCourseStores() throws Exception {
		CourseStoreResponse storeResponse = new CourseStoreResponse(
			10L, 1, 1, 100L, "소금빵집", 37.5, 126.9, "마포구", "꼭 먹어야 함", LocalTime.of(10, 0), false, null, null, List.of());
		AdminCourseDetailResponse detail = new AdminCourseDetailResponse(
			1L, "망원 빵지순례", "HOT", null, null, "courses/1/cover.jpg", null, null, 1, 10L,
			LocalDateTime.of(2026, 1, 1, 0, 0), List.of(storeResponse));
		given(adminCourseQueryService.getCuratedCourseDetail(1L)).willReturn(detail);

		mockMvc.perform(get("/api/admin/courses/1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.id").value(1))
			.andExpect(jsonPath("$.data.title").value("망원 빵지순례"))
			.andExpect(jsonPath("$.data.storeCount").value(1))
			.andExpect(jsonPath("$.data.courseStores[0].courseStoreId").value(10))
			.andExpect(jsonPath("$.data.courseStores[0].storeName").value("소금빵집"));
	}

	@Test
	void getCourseDetail_returnsNotFoundWhenCourseDeleted() throws Exception {
		given(adminCourseQueryService.getCuratedCourseDetail(99L))
			.willThrow(new CourseException(CourseErrorCode.COURSE_NOT_FOUND));

		mockMvc.perform(get("/api/admin/courses/99"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.success").value(false));
	}

	// ───────────────────────── CRUD ─────────────────────────

	@Test
	void createCourse_returns201WithCourseId() throws Exception {
		given(adminCourseCommandService.createCuratedCourse(any())).willReturn(5L);

		mockMvc.perform(post("/api/admin/courses")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"title\":\"성수 빵지순례\"}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.courseId").value(5));
	}

	@Test
	void updateCourse_returnsOk() throws Exception {
		willDoNothing().given(adminCourseCommandService).updateCuratedCourse(eq(1L), any());

		mockMvc.perform(patch("/api/admin/courses/1")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"title\":\"수정된 제목\",\"badge\":\"NEW\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true));
	}

	@Test
	void updateCourse_returnsNotFoundWhenCourseNotExist() throws Exception {
		willThrow(new CourseException(CourseErrorCode.COURSE_NOT_FOUND))
			.given(adminCourseCommandService).updateCuratedCourse(eq(99L), any());

		mockMvc.perform(patch("/api/admin/courses/99")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"title\":\"없는 코스\"}"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.success").value(false));
	}

	@Test
	void deleteCourse_returnsOk() throws Exception {
		willDoNothing().given(adminCourseCommandService).deleteCuratedCourse(1L);

		mockMvc.perform(delete("/api/admin/courses/1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true));
	}

	// ───────────────────────── 이미지 ─────────────────────────

	@Test
	void getImagePresignedUrl_returnsPresignedUrl() throws Exception {
		given(adminCourseCommandService.getPresignedUrl(eq(1L), eq("image/jpeg")))
			.willReturn(new PresignedUrlResponse("cover.jpg", "image/jpeg", "https://s3.example.com/upload",
				"courses/1/cover.jpg"));

		mockMvc.perform(post("/api/admin/courses/1/images/presigned-url")
			.param("mimeType", "image/jpeg"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.uploadUrl").value("https://s3.example.com/upload"))
			.andExpect(jsonPath("$.data.awsKey").value("courses/1/cover.jpg"));
	}

	@Test
	void registerImage_returnsOk() throws Exception {
		willDoNothing().given(adminCourseCommandService).registerImage(eq(1L), any());

		mockMvc.perform(post("/api/admin/courses/1/images")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"awsKey\":\"courses/1/cover.jpg\",\"mimeType\":\"image/jpeg\",\"name\":\"cover.jpg\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true));
	}

	@Test
	void deleteImage_returnsOk() throws Exception {
		willDoNothing().given(adminCourseCommandService).deleteImage(1L);

		mockMvc.perform(delete("/api/admin/courses/1/images"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true));
	}

	@Test
	void deleteImage_returnsNotFoundWhenImageNotExist() throws Exception {
		willThrow(new CourseException(CourseErrorCode.COURSE_IMAGE_NOT_FOUND))
			.given(adminCourseCommandService).deleteImage(1L);

		mockMvc.perform(delete("/api/admin/courses/1/images"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.success").value(false));
	}

	// ───────────────────────── 가게 관리 ─────────────────────────

	@Test
	void addStore_returnsCreatedCourseStore() throws Exception {
		CourseStoreResponse storeResponse = new CourseStoreResponse(
			10L, 1, 1, 100L, "소금빵집", 37.5, 126.9, "마포구", null, null, false, null, null, List.of());
		given(adminCourseCommandService.addStore(eq(1L), any())).willReturn(storeResponse);

		mockMvc.perform(post("/api/admin/courses/1/stores")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"storeId\":100}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.courseStoreId").value(10))
			.andExpect(jsonPath("$.data.storeName").value("소금빵집"))
			.andExpect(jsonPath("$.data.visitOrder").value(1));
	}

	@Test
	void addStore_returnsBadRequestWhenAlreadyExists() throws Exception {
		given(adminCourseCommandService.addStore(eq(1L), any()))
			.willThrow(new CourseException(CourseErrorCode.COURSE_STORE_ALREADY_EXISTS));

		mockMvc.perform(post("/api/admin/courses/1/stores")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"storeId\":100}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false));
	}

	@Test
	void updateStore_returnsOk() throws Exception {
		willDoNothing().given(adminCourseCommandService).updateStore(eq(1L), eq(100L), any());

		mockMvc.perform(patch("/api/admin/courses/1/stores/100")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"memo\":\"꼭 가야 함\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true));
	}

	@Test
	void removeStore_returnsOk() throws Exception {
		willDoNothing().given(adminCourseCommandService).removeStore(1L, 100L);

		mockMvc.perform(delete("/api/admin/courses/1/stores/100"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true));
	}

	@Test
	void reorderStores_returnsOk() throws Exception {
		willDoNothing().given(adminCourseCommandService).reorderStores(eq(1L), any());

		mockMvc.perform(patch("/api/admin/courses/1/stores/reorder")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"courseStoreOrders\":[{\"courseStoreId\":10},{\"courseStoreId\":20}]}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true));
	}
}
