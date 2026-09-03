package sweetgirl.croissant.course.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.LocalDateTime;
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
import sweetgirl.croissant.course.dto.response.CourseInviteAcceptResponse;
import sweetgirl.croissant.course.dto.response.CourseInvitePreviewResponse;
import sweetgirl.croissant.course.dto.response.CourseInviteResponse;
import sweetgirl.croissant.course.dto.response.CourseProgressResponse;
import sweetgirl.croissant.course.dto.response.CourseStoreResponse;
import sweetgirl.croissant.course.dto.response.CourseSummaryResponse;
import sweetgirl.croissant.course.enums.CourseInviteState;
import sweetgirl.croissant.course.enums.CourseProgressStatus;
import sweetgirl.croissant.course.enums.CourseType;
import sweetgirl.croissant.course.exception.CourseException;
import sweetgirl.croissant.course.service.CourseCommandService;
import sweetgirl.croissant.course.service.CourseMemberService;
import sweetgirl.croissant.course.service.CourseProgressService;
import sweetgirl.croissant.course.service.CourseQueryService;
import sweetgirl.croissant.course.service.SavedCourseCommandService;
import sweetgirl.croissant.external.aws.dto.PresignedUrlResponse;
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
	@Mock
	private CourseProgressService courseProgressService;
	@Mock
	private CourseMemberService courseMemberService;

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
			1L, "소금빵 투어", null, null, List.of(), List.of("김진환제과점", "성심당"), 2, List.of(11L, 12L), List.of("마포구"),
			CourseType.CURATED, "어드민",
			null, 0L, 1, 37L, null, null, null);
		given(courseQueryService.getCurationCourses()).willReturn(List.of(response));

		mockMvc.perform(get("/courses"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data[0].id").value(1))
			.andExpect(jsonPath("$.data[0].title").value("소금빵 투어"))
			.andExpect(jsonPath("$.data[0].courseType").value("CURATED"))
			.andExpect(jsonPath("$.data[0].pinOrder").value(1))
			.andExpect(jsonPath("$.data[0].finisherCount").value(37))
			.andExpect(jsonPath("$.data[0].storeNames[0]").value("김진환제과점"));
	}

	@Test
	void getMyCourses_returns200() throws Exception {
		CourseSummaryResponse response = new CourseSummaryResponse(
			2L, "나의 코스", null, null, List.of(), List.of("성심당"), 1, List.of(21L), List.of("서대문구"), CourseType.CUSTOM,
			"user1", null,
			0L, null, 0L, null, null, null);
		given(courseQueryService.getMyCourses(isNull())).willReturn(List.of(response));

		mockMvc.perform(get("/courses/my"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data[0].id").value(2))
			.andExpect(jsonPath("$.data[0].courseType").value("CUSTOM"))
			// 가게 상세의 '빵투어에 담기'가 이미 담긴 코스를 이 필드로 가려낸다
			.andExpect(jsonPath("$.data[0].storeIds[0]").value(21));
	}

	@Test
	void getSavedCourses_returns200() throws Exception {
		CourseSummaryResponse response = new CourseSummaryResponse(
			3L, "찜한 코스", null, null, List.of(), List.of("성심당"), 3, List.of(31L, 32L, 33L), List.of("강남구"),
			CourseType.CURATED, "어드민",
			null, 0L, null, 0L, null, null, null);
		given(courseQueryService.getSavedCourses(isNull())).willReturn(List.of(response));

		mockMvc.perform(get("/courses/saved"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data[0].id").value(3));
	}

	@Test
	void getCourseProgresses_returns200() throws Exception {
		CourseProgressResponse response = new CourseProgressResponse(
			1L, "소금빵 투어", null, null, List.of(), 4, 2, 50,
			LocalDateTime.of(2026, 8, 1, 10, 0), LocalDateTime.of(2026, 8, 3, 10, 0),
			CourseProgressStatus.IN_PROGRESS, null);
		given(courseProgressService.getOngoing(isNull())).willReturn(List.of(response));

		mockMvc.perform(get("/courses/progress"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data[0].id").value(1))
			.andExpect(jsonPath("$.data[0].visitedStoreCount").value(2))
			.andExpect(jsonPath("$.data[0].progressPercent").value(50));
	}

	@Test
	void startCourseProgress_returns200() throws Exception {
		mockMvc.perform(post("/courses/1/progress"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true));
	}

	@Test
	void stopCourseProgress_returns200() throws Exception {
		mockMvc.perform(delete("/courses/1/progress"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true));
	}

	@Test
	void getCourseDetail_returns200() throws Exception {
		CourseDetailResponse response = new CourseDetailResponse(
			1L, "소금빵 투어", null, null, List.of(), 2, List.of("마포구"), CourseType.CURATED, "어드민", null, null, true, false,
			false, 5L, 37L, LocalDateTime.of(2026, 1, 1, 0, 0), "서울", LocalDate.of(2026, 8, 20),
			LocalDate.of(2026, 8, 21), List.of());
		given(courseQueryService.getCourseDetail(anyLong(), isNull())).willReturn(response);

		mockMvc.perform(get("/courses/1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.id").value(1))
			.andExpect(jsonPath("$.data.isPublic").value(true))
			.andExpect(jsonPath("$.data.savedCount").value(5))
			.andExpect(jsonPath("$.data.finisherCount").value(37))
			.andExpect(jsonPath("$.data.region").value("서울"))
			.andExpect(jsonPath("$.data.startDate").value("2026-08-20"))
			.andExpect(jsonPath("$.data.endDate").value("2026-08-21"))
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
	void createCourse_withPlan_returns201() throws Exception {
		given(courseCommandService.createCourse(isNull(), any())).willReturn(11L);

		mockMvc.perform(post("/courses")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"title\":\"부산 빵투어\",\"isPublic\":true,\"region\":\"부산\","
				+ "\"startDate\":\"2026-08-20\",\"endDate\":\"2026-08-21\"}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.data.courseId").value(11));
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

	@Test
	void getPresignedUrl_returns200() throws Exception {
		given(courseCommandService.getPresignedUrl(isNull(), anyLong(), any()))
			.willReturn(new PresignedUrlResponse(
				"cover.jpg", "image/jpeg", "https://s3.example.com/upload", "courses/1/cover.jpg"));

		mockMvc.perform(post("/courses/1/images/presigned-url")
			.param("mimeType", "image/jpeg"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.awsKey").value("courses/1/cover.jpg"));
	}

	@Test
	void registerImage_returns200() throws Exception {
		willDoNothing().given(courseCommandService).registerImage(isNull(), anyLong(), any());

		mockMvc.perform(post("/courses/1/images")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"awsKey\":\"courses/1/cover.jpg\",\"mimeType\":\"image/jpeg\",\"name\":\"cover.jpg\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true));
	}

	@Test
	void deleteImage_returns200() throws Exception {
		willDoNothing().given(courseCommandService).deleteImage(isNull(), anyLong());

		mockMvc.perform(delete("/courses/1/images"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true));
	}

	@Test
	void deleteImage_notFound_returns404() throws Exception {
		willThrow(new CourseException(CourseErrorCode.COURSE_IMAGE_NOT_FOUND))
			.given(courseCommandService).deleteImage(isNull(), anyLong());

		mockMvc.perform(delete("/courses/1/images"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.code").value("COURSE404_3"));
	}

	@Test
	void addStore_returns200() throws Exception {
		CourseStoreResponse storeResponse = new CourseStoreResponse(
			100L, 1, 1, 10L, "소금빵집", 37.5, 126.9, "마포구", null, null, false, null);
		given(courseCommandService.addStore(isNull(), anyLong(), any())).willReturn(storeResponse);

		mockMvc.perform(post("/courses/1/stores")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"storeId\":10}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.storeId").value(10))
			.andExpect(jsonPath("$.data.visitOrder").value(1));
	}

	@Test
	void addStore_duplicate_returns400() throws Exception {
		willThrow(new CourseException(CourseErrorCode.COURSE_STORE_ALREADY_EXISTS))
			.given(courseCommandService).addStore(isNull(), anyLong(), any());

		mockMvc.perform(post("/courses/1/stores")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"storeId\":10}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.code").value("COURSE400_1"));
	}

	@Test
	void updateStore_returns200() throws Exception {
		willDoNothing().given(courseCommandService).updateStore(isNull(), anyLong(), anyLong(), any());

		mockMvc.perform(patch("/courses/1/stores/10")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"memo\":\"맛있어요\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true));
	}

	@Test
	void removeStore_returns200() throws Exception {
		willDoNothing().given(courseCommandService).removeStore(isNull(), anyLong(), anyLong());

		mockMvc.perform(delete("/courses/1/stores/10"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true));
	}

	@Test
	void reorderStores_returns200() throws Exception {
		willDoNothing().given(courseCommandService).reorderStores(isNull(), anyLong(), any());

		mockMvc.perform(patch("/courses/1/stores/reorder")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"courseStoreOrders\":[{\"courseStoreId\":1},{\"courseStoreId\":2}]}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true));
	}

	@Test
	void saveCourse_returns200() throws Exception {
		willDoNothing().given(savedCourseCommandService).saveCourse(isNull(), anyLong());

		mockMvc.perform(post("/courses/1/save"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true));
	}

	@Test
	void saveCourse_duplicate_returns400() throws Exception {
		willThrow(new CourseException(CourseErrorCode.COURSE_SAVED_ALREADY))
			.given(savedCourseCommandService).saveCourse(isNull(), anyLong());

		mockMvc.perform(post("/courses/1/save"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.code").value("COURSE400_2"));
	}

	@Test
	void unsaveCourse_returns200() throws Exception {
		willDoNothing().given(savedCourseCommandService).unsaveCourse(isNull(), anyLong());

		mockMvc.perform(delete("/courses/1/save"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true));
	}

	@Test
	void unsaveCourse_notFound_returns404() throws Exception {
		willThrow(new CourseException(CourseErrorCode.COURSE_SAVED_NOT_FOUND))
			.given(savedCourseCommandService).unsaveCourse(isNull(), anyLong());

		mockMvc.perform(delete("/courses/1/save"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.code").value("COURSE404_4"));
	}

	@Test
	void forkCourse_returns201() throws Exception {
		given(courseCommandService.forkCourse(isNull(), anyLong())).willReturn(20L);

		mockMvc.perform(post("/courses/1/fork"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.courseId").value(20));
	}

	@Test
	void forkCourse_notPublic_returns400() throws Exception {
		willThrow(new CourseException(CourseErrorCode.COURSE_FORK_NOT_PUBLIC))
			.given(courseCommandService).forkCourse(isNull(), anyLong());

		mockMvc.perform(post("/courses/1/fork"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.code").value("COURSE400_3"));
	}

	// ───────────────────────── 동시편집: 초대·멤버 ─────────────────────────

	@Test
	void createInvite_returns200() throws Exception {
		LocalDateTime expiresAt = LocalDateTime.of(2026, 9, 10, 14, 3);
		given(courseMemberService.createInvite(isNull(), eq(10L)))
			.willReturn(new CourseInviteResponse("k9J-token", expiresAt, false));

		mockMvc.perform(post("/courses/10/invites"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.token").value("k9J-token"))
			.andExpect(jsonPath("$.data.expiresAt").value("2026-09-10T14:03:00"))
			.andExpect(jsonPath("$.data.reused").value(false));
	}

	@Test
	void previewInvite_returns200_withStateAndNoViewerWhenAnonymous() throws Exception {
		given(courseMemberService.previewInvite(eq("abc_-123"), isNull()))
			.willReturn(new CourseInvitePreviewResponse(
				CourseInviteState.OK, 12L, "성수 소금빵 투어", "다나", 3, 8,
				LocalDateTime.of(2026, 9, 10, 14, 3), null));

		mockMvc.perform(get("/courses/invites/abc_-123"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.state").value("OK"))
			.andExpect(jsonPath("$.data.courseId").value(12))
			.andExpect(jsonPath("$.data.ownerName").value("다나"))
			.andExpect(jsonPath("$.data.memberCount").value(3))
			.andExpect(jsonPath("$.data.memberLimit").value(8))
			.andExpect(jsonPath("$.data.viewer").doesNotExist());
	}

	@Test
	void previewInvite_invalidToken_stillReturns200() throws Exception {
		given(courseMemberService.previewInvite(eq("nope"), isNull()))
			.willReturn(CourseInvitePreviewResponse.invalid());

		mockMvc.perform(get("/courses/invites/nope"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.state").value("INVALID"))
			.andExpect(jsonPath("$.data.courseId").doesNotExist());
	}

	@Test
	void acceptInvite_returns200() throws Exception {
		given(courseMemberService.acceptInvite(isNull(), eq("abc"), eq("지수")))
			.willReturn(new CourseInviteAcceptResponse(12L, false));

		mockMvc.perform(post("/courses/invites/abc/accept")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"displayName\":\"지수\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.courseId").value(12))
			.andExpect(jsonPath("$.data.alreadyMember").value(false));
	}

	@Test
	void acceptInvite_blankOrTooLongName_returns400() throws Exception {
		mockMvc.perform(post("/courses/invites/abc/accept")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"displayName\":\"   \"}"))
			.andExpect(status().isBadRequest());
		mockMvc.perform(post("/courses/invites/abc/accept")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"displayName\":\"12345678901\"}"))
			.andExpect(status().isBadRequest());
		then(courseMemberService).shouldHaveNoInteractions();
	}

	@Test
	void leaveCourse_returns200() throws Exception {
		willDoNothing().given(courseMemberService).leave(isNull(), eq(10L));

		mockMvc.perform(delete("/courses/10/members/me"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true));
		then(courseMemberService).should().leave(null, 10L);
	}

	@Test
	void leaveCourse_noSuccessor_returns400() throws Exception {
		willThrow(new CourseException(CourseErrorCode.COURSE_NO_SUCCESSOR))
			.given(courseMemberService).leave(isNull(), eq(10L));

		mockMvc.perform(delete("/courses/10/members/me"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COURSE400_17"));
	}

	@Test
	void renameMember_returns200() throws Exception {
		mockMvc.perform(patch("/courses/10/members/me")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"displayName\":\"빵순이\"}"))
			.andExpect(status().isOk());
		then(courseMemberService).should().rename(null, 10L, "빵순이");
	}
}
