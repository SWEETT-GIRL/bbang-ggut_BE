package sweetgirl.croissant.course.service;

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
import sweetgirl.croissant.course.dto.response.CourseDetailResponse;
import sweetgirl.croissant.course.dto.response.CourseSummaryResponse;
import sweetgirl.croissant.course.entity.Course;
import sweetgirl.croissant.course.entity.CourseImageFile;
import sweetgirl.croissant.course.entity.CourseStore;
import sweetgirl.croissant.course.entity.SavedCourse;
import sweetgirl.croissant.course.enums.CourseType;
import sweetgirl.croissant.course.exception.CourseException;
import sweetgirl.croissant.course.repository.CourseImageFileRepository;
import sweetgirl.croissant.course.repository.CourseRepository;
import sweetgirl.croissant.course.repository.CourseStoreRepository;
import sweetgirl.croissant.course.repository.SavedCourseRepository;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.store.entity.Store;

@ExtendWith(MockitoExtension.class)
class CourseQueryServiceTest {

	@Mock
	CourseRepository courseRepository;
	@Mock
	CourseStoreRepository courseStoreRepository;
	@Mock
	SavedCourseRepository savedCourseRepository;
	@Mock
	CourseImageFileRepository courseImageFileRepository;

	@InjectMocks
	CourseQueryService courseQueryService;

	// ───────────────────────── getCurationCourses ─────────────────────────

	@Test
	@DisplayName("getCurationCourses: 공개 코스 목록을 반환한다")
	void getCurationCourses_success() {
		Course course = mockCourse(1L, "망원동 빵지순례", true, CourseType.CURATED, null);
		CourseStore courseStore = mockCourseStore(1, "빵집A", "마포구");

		given(courseRepository.findAllByIsPublicTrueAndDeletedAtIsNull()).willReturn(List.of(course));
		given(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(1L))
			.willReturn(List.of(courseStore));
		given(courseImageFileRepository.findByCourseId(1L)).willReturn(Optional.empty());

		List<CourseSummaryResponse> result = courseQueryService.getCurationCourses();

		assertThat(result).hasSize(1);
		assertThat(result.get(0).id()).isEqualTo(1L);
		assertThat(result.get(0).title()).isEqualTo("망원동 빵지순례");
		assertThat(result.get(0).storeCount()).isEqualTo(1);
		assertThat(result.get(0).regions()).containsExactly("마포구");
	}

	// ───────────────────────── getMyCourses ─────────────────────────

	@Test
	@DisplayName("getMyCourses: CUSTOM + FORKED 내 코스 목록을 반환한다")
	void getMyCourses_success() {
		Long memberId = 10L;
		Course custom = mockCourse(1L, "나의 코스", true, CourseType.CUSTOM, memberId);
		Course forked = mockCourse(2L, "copy of 큐레이션", false, CourseType.FORKED, memberId);

		given(courseRepository.findAllByMemberIdAndTypeInAndDeletedAtIsNull(
			memberId, List.of(CourseType.CUSTOM, CourseType.FORKED)))
			.willReturn(List.of(custom, forked));
		given(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(1L))
			.willReturn(List.of());
		given(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(2L))
			.willReturn(List.of());
		given(courseImageFileRepository.findByCourseId(1L)).willReturn(Optional.empty());
		given(courseImageFileRepository.findByCourseId(2L)).willReturn(Optional.empty());

		List<CourseSummaryResponse> result = courseQueryService.getMyCourses(memberId);

		assertThat(result).hasSize(2);
		assertThat(result.get(0).courseType()).isEqualTo(CourseType.CUSTOM);
		assertThat(result.get(1).courseType()).isEqualTo(CourseType.FORKED);
	}

	// ───────────────────────── getSavedCourses ─────────────────────────

	@Test
	@DisplayName("getSavedCourses: 찜한 코스 목록을 반환하며 삭제된 코스는 제외한다")
	void getSavedCourses_success() {
		Long memberId = 10L;
		Course activeCourse = mockCourse(1L, "찜한 코스", true, CourseType.CURATED, null);

		Course deletedCourse = mock(Course.class);
		given(deletedCourse.isDeleted()).willReturn(true);

		SavedCourse saved1 = mock(SavedCourse.class);
		given(saved1.getCourse()).willReturn(activeCourse);
		SavedCourse saved2 = mock(SavedCourse.class);
		given(saved2.getCourse()).willReturn(deletedCourse);

		given(savedCourseRepository.findAllByMemberId(memberId)).willReturn(List.of(saved1, saved2));
		given(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(1L))
			.willReturn(List.of());
		given(courseImageFileRepository.findByCourseId(1L)).willReturn(Optional.empty());

		List<CourseSummaryResponse> result = courseQueryService.getSavedCourses(memberId);

		assertThat(result).hasSize(1);
		assertThat(result.get(0).id()).isEqualTo(1L);
	}

	// ───────────────────────── getCourseDetail ─────────────────────────

	@Test
	@DisplayName("getCourseDetail: 공개 코스를 비로그인으로 조회하면 성공한다")
	void getCourseDetail_publicCourse_notLoggedIn_success() {
		Course course = mockCourse(1L, "공개 코스", true, CourseType.CURATED, null);
		CourseStore courseStore = mockCourseStore(1, "빵집A", "종로구");
		CourseImageFile imageFile = mock(CourseImageFile.class);
		given(imageFile.getAwsKey()).willReturn("courses/1/cover.jpg");

		given(courseRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(course));
		given(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(1L))
			.willReturn(List.of(courseStore));
		given(courseImageFileRepository.findByCourseId(1L)).willReturn(Optional.of(imageFile));
		given(savedCourseRepository.countByCourseId(1L)).willReturn(5L);

		CourseDetailResponse result = courseQueryService.getCourseDetail(1L, null);

		assertThat(result.id()).isEqualTo(1L);
		assertThat(result.isSaved()).isFalse();
		assertThat(result.savedCount()).isEqualTo(5L);
		assertThat(result.imageKey()).isEqualTo("courses/1/cover.jpg");
		assertThat(result.courseStores()).hasSize(1);
		assertThat(result.regions()).containsExactly("종로구");
	}

	@Test
	@DisplayName("getCourseDetail: 비공개 코스를 소유자가 조회하면 성공한다")
	void getCourseDetail_privateCourse_owner_success() {
		Long memberId = 10L;
		Course course = mockCourse(1L, "비공개 코스", false, CourseType.CUSTOM, memberId);

		given(courseRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(course));
		given(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(1L))
			.willReturn(List.of());
		given(courseImageFileRepository.findByCourseId(1L)).willReturn(Optional.empty());
		given(savedCourseRepository.existsByMemberIdAndCourseId(memberId, 1L)).willReturn(false);
		given(savedCourseRepository.countByCourseId(1L)).willReturn(0L);

		CourseDetailResponse result = courseQueryService.getCourseDetail(1L, memberId);

		assertThat(result.id()).isEqualTo(1L);
	}

	@Test
	@DisplayName("getCourseDetail: 비공개 코스를 타인이 조회하면 COURSE_FORBIDDEN을 던진다")
	void getCourseDetail_privateCourse_otherMember_throwsForbidden() {
		Long ownerId = 10L;
		Long otherId = 99L;

		Course course = mock(Course.class);
		Member owner = mock(Member.class);
		given(owner.getId()).willReturn(ownerId);
		given(course.isPublic()).willReturn(false);
		given(course.getMember()).willReturn(owner);
		given(courseRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(course));

		assertThatThrownBy(() -> courseQueryService.getCourseDetail(1L, otherId))
			.isInstanceOf(CourseException.class)
			.hasMessageContaining(CourseErrorCode.COURSE_FORBIDDEN.getMessage());
	}

	@Test
	@DisplayName("getCourseDetail: 삭제된 코스를 조회하면 COURSE_NOT_FOUND를 던진다")
	void getCourseDetail_deletedCourse_throwsNotFound() {
		given(courseRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.empty());

		assertThatThrownBy(() -> courseQueryService.getCourseDetail(1L, null))
			.isInstanceOf(CourseException.class)
			.hasMessageContaining(CourseErrorCode.COURSE_NOT_FOUND.getMessage());
	}

	// ───────────────────────── helpers ─────────────────────────

	private Course mockCourse(Long id, String title, boolean isPublic, CourseType type, Long memberId) {
		Course course = mock(Course.class);
		given(course.getId()).willReturn(id);
		given(course.getTitle()).willReturn(title);
		// getCourseDetail에서만 호출됨 — 다른 테스트에서 호출 안 될 수 있으므로 lenient
		lenient().when(course.isPublic()).thenReturn(isPublic);
		given(course.getType()).willReturn(type);
		given(course.getBadge()).willReturn(null);
		if (memberId != null) {
			Member member = mock(Member.class);
			// 접근 제어 체크에서만 사용 — summary 빌드 시 미호출
			lenient().when(member.getId()).thenReturn(memberId);
			given(member.getNickname()).willReturn("테스터");
			given(course.getMember()).willReturn(member);
		}
		return course;
	}

	private CourseStore mockCourseStore(int visitOrder, String storeName, String region2nd) {
		CourseStore courseStore = mock(CourseStore.class);
		Store store = mock(Store.class);
		Point location = mock(Point.class);

		// CourseStoreResponse.from() 호출 시에만 필요 — summary 빌드 시 미호출
		lenient().when(courseStore.getVisitOrder()).thenReturn(visitOrder);
		given(courseStore.getStore()).willReturn(store);
		lenient().when(courseStore.getMemo()).thenReturn(null);
		lenient().when(courseStore.getVisitTime()).thenReturn(null);
		lenient().when(courseStore.getWalkingMinutesToNext()).thenReturn(null);
		lenient().when(store.getId()).thenReturn(1L);
		lenient().when(store.getName()).thenReturn(storeName);
		given(store.getRegion2nd()).willReturn(region2nd);
		lenient().when(store.getLocation()).thenReturn(location);
		lenient().when(location.getY()).thenReturn(37.565);
		lenient().when(location.getX()).thenReturn(126.977);
		return courseStore;
	}
}
