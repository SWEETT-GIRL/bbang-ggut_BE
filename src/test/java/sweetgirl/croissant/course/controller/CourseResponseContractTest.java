package sweetgirl.croissant.course.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;

import sweetgirl.croissant.course.dto.response.CourseDetailLookup;
import sweetgirl.croissant.course.dto.response.CourseDetailResponse;
import sweetgirl.croissant.course.dto.response.CourseMemberResponse;
import sweetgirl.croissant.course.dto.response.CourseStoreMemoResponse;
import sweetgirl.croissant.course.dto.response.CourseStoreResponse;
import sweetgirl.croissant.course.dto.response.CourseSummaryResponse;
import sweetgirl.croissant.course.enums.CourseType;
import sweetgirl.croissant.course.enums.CourseVisibility;
import sweetgirl.croissant.course.service.CourseCommandService;
import sweetgirl.croissant.course.service.CourseMemberService;
import sweetgirl.croissant.course.service.CourseProgressService;
import sweetgirl.croissant.course.service.CourseQueryService;
import sweetgirl.croissant.course.service.CourseStoreVisitService;
import sweetgirl.croissant.course.service.SavedCourseCommandService;
import sweetgirl.croissant.global.contract.ResponseContractWebMvcTest;
import sweetgirl.croissant.store.enums.StoreCategory;

/**
 * 앱이 실제로 읽는 코스 목록·상세 응답 필드를 고정한다. 주석의 파일:라인은 앱 레포 기준 사용처다.
 */
@ContextConfiguration(classes = CourseController.class)
class CourseResponseContractTest extends ResponseContractWebMvcTest {

	@MockitoBean
	private CourseQueryService courseQueryService;

	@MockitoBean
	private CourseCommandService courseCommandService;

	@MockitoBean
	private SavedCourseCommandService savedCourseCommandService;

	@MockitoBean
	private CourseProgressService courseProgressService;

	@MockitoBean
	private CourseStoreVisitService courseStoreVisitService;

	@MockitoBean
	private CourseMemberService courseMemberService;

	@Test
	void curationCourses_keepsFieldsTheAppReads() throws Exception {
		given(courseQueryService.getCurationCourses()).willReturn(summaries());

		expectSummaryFields(mockMvc.perform(get("/courses")));
	}

	@Test
	void myCourses_keepsFieldsTheAppReads() throws Exception {
		given(courseQueryService.getMyCourses(any())).willReturn(summaries());

		expectSummaryFields(mockMvc.perform(get("/courses/my")));
	}

	@Test
	void savedCourses_keepsFieldsTheAppReads() throws Exception {
		given(courseQueryService.getSavedCourses(any())).willReturn(summaries());

		expectSummaryFields(mockMvc.perform(get("/courses/saved")));
	}

	@Test
	void detail_keepsFieldsTheAppReads() throws Exception {
		CourseStoreResponse stop = new CourseStoreResponse(501L, 1, 1, 11L, "성심당", 36.3276, 127.4273, "대전 중구",
			"튀김소보로 먼저", LocalTime.of(10, 30), true, 601L, "내 메모",
			List.of(new CourseStoreMemoResponse(7L, "소보로", "같이 가요", LocalDateTime.of(2026, 10, 1, 9, 0), true)),
			StoreCategory.BAKERY);
		CourseDetailResponse detail = new CourseDetailResponse(1L, "대전 빵지순례", "courses/1.jpg", "courses/640/1.jpg",
			List.of("stores/11/320.jpg"), 1, List.of("대전"), CourseType.CUSTOM, "소보로", 7L, "추천", true, true, true, 12L,
			3L, LocalDateTime.of(2026, 9, 30, 18, 0), "대전", LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 11),
			List.of(stop), true, true, true, 2, List.of(new CourseMemberResponse(7L, "소보로", true, true)), "소보로", 2,
			CourseVisibility.PUBLIC, "share-token", 4L);
		given(courseQueryService.getCourseDetail(eq(1L), any(), isNull(), any()))
			.willReturn(CourseDetailLookup.of(detail));

		mockMvc.perform(get("/courses/1"))
			.andExpect(status().isOk())
			// app/course/[id].tsx:2896,1642
			.andExpect(jsonPath("$.data.title").isString())
			// [id].tsx:383,395,396 — 키 이름이 is 로 시작해야 한다
			.andExpect(jsonPath("$.data.isMine").isBoolean())
			.andExpect(jsonPath("$.data.canEdit").isBoolean())
			.andExpect(jsonPath("$.data.isMember").isBoolean())
			// [id].tsx:1799
			.andExpect(jsonPath("$.data.isPublic").isBoolean())
			.andExpect(jsonPath("$.data.visibility").value("PUBLIC"))
			// [id].tsx:1975,2959
			.andExpect(jsonPath("$.data.isSaved").isBoolean())
			.andExpect(jsonPath("$.data.savedCount").isNumber())
			// [id].tsx:2077
			.andExpect(jsonPath("$.data.authorId").isNumber())
			// [id].tsx:1054
			.andExpect(jsonPath("$.data.createdAt").isString())
			// [id].tsx:480-500
			.andExpect(jsonPath("$.data.region").isString())
			.andExpect(jsonPath("$.data.startDate").value("2026-10-10"))
			.andExpect(jsonPath("$.data.endDate").value("2026-10-11"))
			.andExpect(jsonPath("$.data.dayCount").isNumber())
			// [id].tsx:954,673,683
			.andExpect(jsonPath("$.data.memberCount").isNumber())
			.andExpect(jsonPath("$.data.members").isArray())
			.andExpect(jsonPath("$.data.myDisplayName").isString())
			// [id].tsx:830 — 동시 편집 충돌 감지(ETag)에 쓴다
			.andExpect(jsonPath("$.data.version").isNumber())
			// [id].tsx:416-418 — courseStores 에 ?. 가 없어 없으면 크래시
			.andExpect(jsonPath("$.data.courseStores").isArray())
			// [id].tsx:226,215,490
			.andExpect(jsonPath("$.data.courseStores[0].courseStoreId").isNumber())
			.andExpect(jsonPath("$.data.courseStores[0].visitOrder").isNumber())
			.andExpect(jsonPath("$.data.courseStores[0].day").isNumber())
			.andExpect(jsonPath("$.data.courseStores[0].storeId").isNumber())
			// [id].tsx:985
			.andExpect(jsonPath("$.data.courseStores[0].storeName").isString())
			.andExpect(jsonPath("$.data.courseStores[0].region").isString())
			// [id].tsx:1061 — 지도 핀
			.andExpect(jsonPath("$.data.courseStores[0].latitude").isNumber())
			.andExpect(jsonPath("$.data.courseStores[0].longitude").isNumber())
			// [id].tsx:3314
			.andExpect(jsonPath("$.data.courseStores[0].memo").isString())
			.andExpect(jsonPath("$.data.courseStores[0].myMemo").isString())
			// [id].tsx:2397
			.andExpect(jsonPath("$.data.courseStores[0].visitTime").isString())
			// [id].tsx:418
			.andExpect(jsonPath("$.data.courseStores[0].visited").isBoolean())
			// [id].tsx:4515
			.andExpect(jsonPath("$.data.courseStores[0].visitedRecordId").isNumber())
			// [id].tsx:2713,4475
			.andExpect(jsonPath("$.data.courseStores[0].memos").isArray());
	}

	private static List<CourseSummaryResponse> summaries() {
		CourseSummaryResponse pinned = new CourseSummaryResponse(1L, "대전 빵지순례", "courses/1.jpg",
			"courses/320/1.jpg", List.of("stores/11/320.jpg"), List.of("성심당"), 1, List.of(11L), List.of("대전"),
			CourseType.CURATED, "빵긋", "인기", "지역", "대전", 12L, 1, 3L, "대전", LocalDate.of(2026, 10, 10),
			LocalDate.of(2026, 10, 11), true);
		CourseSummaryResponse unpinned = new CourseSummaryResponse(2L, "성수 빵지순례", "courses/2.jpg",
			"courses/320/2.jpg", List.of(), List.of(), 0, List.of(), List.of(), CourseType.CUSTOM, "소보로", null, null,
			null, 0L, null, 0L, null, null, null, false);
		return List.of(pinned, unpinned);
	}

	private static void expectSummaryFields(ResultActions result) throws Exception {
		result.andExpect(status().isOk())
			.andExpect(jsonPath("$.data").isArray())
			// explore.tsx:105,109,133
			.andExpect(jsonPath("$.data[0].id").isNumber())
			.andExpect(jsonPath("$.data[0].title").isString())
			// CourseCover.tsx:60,73
			.andExpect(jsonPath("$.data[0].imageKey").isString())
			.andExpect(jsonPath("$.data[0].thumbnailImageKey").isString())
			.andExpect(jsonPath("$.data[0].storeThumbnailKeys").isArray())
			// explore.tsx:142
			.andExpect(jsonPath("$.data[0].storeNames").isArray())
			.andExpect(jsonPath("$.data[0].storeCount").isNumber())
			// SaveToCourseSheet.tsx:150
			.andExpect(jsonPath("$.data[0].storeIds").isArray())
			// explore.tsx:106,347
			.andExpect(jsonPath("$.data[0].regions").isArray())
			// explore.tsx:59
			.andExpect(jsonPath("$.data[0].courseType").value("CURATED"))
			.andExpect(jsonPath("$.data[0].badge").isString())
			// explore.tsx:343-344
			.andExpect(jsonPath("$.data[0].categoryGroup").isString())
			.andExpect(jsonPath("$.data[0].category").isString())
			// explore.tsx:158
			.andExpect(jsonPath("$.data[0].savedCount").isNumber())
			// explore.tsx:122 — pinOrder != null 로 고정 코스를 가른다. 없을 때 키가 빠지지 않고 null 로 와야 한다
			.andExpect(jsonPath("$.data[0].pinOrder").value(1))
			.andExpect(jsonPath("$.data[1].pinOrder").value(Matchers.nullValue()))
			// utils/course.ts:13
			.andExpect(jsonPath("$.data[0].authorName").isString())
			// CourseListCard.tsx:43,48
			.andExpect(jsonPath("$.data[0].startDate").value("2026-10-10"))
			.andExpect(jsonPath("$.data[0].endDate").value("2026-10-11"))
			.andExpect(jsonPath("$.data[0].isMember").value(true));
	}
}
