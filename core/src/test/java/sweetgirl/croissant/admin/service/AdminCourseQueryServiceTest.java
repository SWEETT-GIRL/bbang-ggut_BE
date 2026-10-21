package sweetgirl.croissant.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.locationtech.jts.geom.Point;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.course.code.CourseErrorCode;
import sweetgirl.croissant.course.dto.response.AdminCourseDetailResponse;
import sweetgirl.croissant.course.dto.response.AdminCourseResponse;
import sweetgirl.croissant.course.entity.Course;
import sweetgirl.croissant.course.entity.CourseStore;
import sweetgirl.croissant.course.enums.CourseType;
import sweetgirl.croissant.course.exception.CourseException;
import sweetgirl.croissant.course.repository.CourseImageFileRepository;
import sweetgirl.croissant.course.repository.CourseRepository;
import sweetgirl.croissant.course.repository.CourseStoreRepository;
import sweetgirl.croissant.course.repository.SavedCourseRepository;
import sweetgirl.croissant.store.entity.Store;

@ExtendWith(MockitoExtension.class)
class AdminCourseQueryServiceTest {

	@Mock
	CourseRepository courseRepository;
	@Mock
	CourseStoreRepository courseStoreRepository;
	@Mock
	CourseImageFileRepository courseImageFileRepository;
	@Mock
	SavedCourseRepository savedCourseRepository;

	@InjectMocks
	AdminCourseQueryService adminCourseQueryService;

	// ───────────────────────── getCuratedCourses ─────────────────────────

	@Test
	@DisplayName("getCuratedCourses: CURATED 코스 목록을 반환한다")
	void getCuratedCourses_success() {
		Course course = mockCourse(1L, "망원동 빵지순례");

		given(courseRepository.findAllByTypeAndDeletedAtIsNullOrderByCreatedAtDesc(CourseType.CURATED))
			.willReturn(List.of(course));
		given(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(1L))
			.willReturn(List.of());
		given(courseImageFileRepository.findByCourseId(1L)).willReturn(Optional.empty());
		given(savedCourseRepository.countByCourseId(1L)).willReturn(3L);

		List<AdminCourseResponse> result = adminCourseQueryService.getCuratedCourses();

		assertThat(result).hasSize(1);
		assertThat(result.get(0).id()).isEqualTo(1L);
		assertThat(result.get(0).title()).isEqualTo("망원동 빵지순례");
		assertThat(result.get(0).savedCount()).isEqualTo(3L);
		assertThat(result.get(0).storeCount()).isZero();
	}

	@Test
	@DisplayName("getCuratedCourses: 코스가 없으면 빈 목록을 반환한다")
	void getCuratedCourses_empty() {
		given(courseRepository.findAllByTypeAndDeletedAtIsNullOrderByCreatedAtDesc(CourseType.CURATED))
			.willReturn(List.of());

		List<AdminCourseResponse> result = adminCourseQueryService.getCuratedCourses();

		assertThat(result).isEmpty();
	}

	// ───────────────────────── getCuratedCourseDetail ─────────────────────────

	@Test
	@DisplayName("getCuratedCourseDetail: 코스 상세를 courseStores 포함하여 반환한다")
	void getCuratedCourseDetail_success() {
		Course course = mockCourse(1L, "망원동 빵지순례");
		CourseStore courseStore = mockCourseStore(1L, 1, "빵집A", "마포구");

		given(courseRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(course));
		given(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(1L))
			.willReturn(List.of(courseStore));
		given(courseImageFileRepository.findByCourseId(1L)).willReturn(Optional.empty());
		given(savedCourseRepository.countByCourseId(1L)).willReturn(5L);

		AdminCourseDetailResponse result = adminCourseQueryService.getCuratedCourseDetail(1L);

		assertThat(result.id()).isEqualTo(1L);
		assertThat(result.savedCount()).isEqualTo(5L);
		assertThat(result.courseStores()).hasSize(1);
		assertThat(result.courseStores().get(0).storeName()).isEqualTo("빵집A");
	}

	@Test
	@DisplayName("getCuratedCourseDetail: 삭제된 코스 조회 시 COURSE_NOT_FOUND를 던진다")
	void getCuratedCourseDetail_deletedCourse_throwsNotFound() {
		given(courseRepository.findByIdAndDeletedAtIsNull(99L)).willReturn(Optional.empty());

		assertThatThrownBy(() -> adminCourseQueryService.getCuratedCourseDetail(99L))
			.isInstanceOf(CourseException.class)
			.hasMessageContaining(CourseErrorCode.COURSE_NOT_FOUND.getMessage());
	}

	// ───────────────────────── helpers ─────────────────────────

	private Course mockCourse(Long id, String title) {
		Course course = mock(Course.class);
		given(course.getId()).willReturn(id);
		given(course.getTitle()).willReturn(title);
		given(course.getBadge()).willReturn(null);
		given(course.getPinOrder()).willReturn(null);
		return course;
	}

	private CourseStore mockCourseStore(Long courseStoreId, int visitOrder, String storeName, String region2nd) {
		CourseStore courseStore = mock(CourseStore.class);
		Store store = mock(Store.class);
		Point location = mock(Point.class);

		given(courseStore.getId()).willReturn(courseStoreId);
		given(courseStore.getVisitOrder()).willReturn(visitOrder);
		given(courseStore.getStore()).willReturn(store);
		lenient().when(courseStore.getMemo()).thenReturn(null);
		lenient().when(courseStore.getVisitTime()).thenReturn(null);
		given(store.getId()).willReturn(1L);
		given(store.getName()).willReturn(storeName);
		given(store.getRegion2nd()).willReturn(region2nd);
		given(store.getLocation()).willReturn(location);
		given(location.getY()).willReturn(37.565);
		given(location.getX()).willReturn(126.977);
		return courseStore;
	}
}
