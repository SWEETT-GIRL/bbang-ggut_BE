package sweetgirl.croissant.course.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.locationtech.jts.geom.Point;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.course.code.CourseErrorCode;
import sweetgirl.croissant.course.dto.request.CourseStoreBulkAddRequest;
import sweetgirl.croissant.course.dto.response.CourseStoreResponse;
import sweetgirl.croissant.course.entity.Course;
import sweetgirl.croissant.course.entity.CourseStore;
import sweetgirl.croissant.course.exception.CourseException;
import sweetgirl.croissant.course.repository.CourseRepository;
import sweetgirl.croissant.course.repository.CourseStoreRepository;
import sweetgirl.croissant.recommendation.code.RecommendationErrorCode;
import sweetgirl.croissant.recommendation.entity.Recommendation;
import sweetgirl.croissant.recommendation.exception.RecommendationException;
import sweetgirl.croissant.recommendation.repository.RecommendationRepository;
import sweetgirl.croissant.store.code.StoreErrorCode;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.exception.StoreException;
import sweetgirl.croissant.store.repository.StoreRepository;

/** bulk 담기(#660)만 본다 — 단건 담기의 나머지 규칙은 CourseCommandServiceTest 가 본다. */
@ExtendWith(MockitoExtension.class)
class CourseCommandServiceBulkAddTest {

	private static final Long MEMBER_ID = 1L;
	private static final Long COURSE_ID = 10L;
	private static final Long RECOMMENDATION_ID = 99L;

	@Mock
	private CourseRepository courseRepository;
	@Mock
	private CourseStoreRepository courseStoreRepository;
	@Mock
	private StoreRepository storeRepository;
	@Mock
	private CourseProgressService courseProgressService;
	@Mock
	private CourseAccessPolicy courseAccessPolicy;
	@Mock
	private RecommendationRepository recommendationRepository;

	@InjectMocks
	private CourseCommandService courseCommandService;

	private Course course;

	private Course lockedCourse() {
		course = mock(Course.class);
		given(courseRepository.findWithLockByIdAndDeletedAtIsNull(COURSE_ID)).willReturn(Optional.of(course));
		return course;
	}

	private static Store store(Long id) {
		Store store = mock(Store.class);
		lenient().when(store.getId()).thenReturn(id);
		Point location = mock(Point.class);
		lenient().when(location.getY()).thenReturn(37.5);
		lenient().when(location.getX()).thenReturn(127.0);
		lenient().when(store.getLocation()).thenReturn(location);
		lenient().when(store.getName()).thenReturn("빵집 " + id);
		return store;
	}

	private static CourseStore existing(Store store, int visitOrder, int day) {
		CourseStore courseStore = mock(CourseStore.class);
		lenient().when(courseStore.getStore()).thenReturn(store);
		lenient().when(courseStore.getVisitOrder()).thenReturn(visitOrder);
		lenient().when(courseStore.getDay()).thenReturn(day);
		return courseStore;
	}

	private static CourseStoreBulkAddRequest request(Long recommendationId, Long... storeIds) {
		List<CourseStoreBulkAddRequest.Item> items = java.util.Arrays.stream(storeIds)
			.map(id -> new CourseStoreBulkAddRequest.Item(id, null))
			.toList();
		return new CourseStoreBulkAddRequest(recommendationId, items);
	}

	private Recommendation recommendationOf(Long courseId) {
		Recommendation recommendation = mock(Recommendation.class);
		given(recommendation.getCourseId()).willReturn(courseId);
		return recommendation;
	}

	@Test
	@DisplayName("빈 코스에 두 곳을 담으면 순서 1·2 로 이어지고, 추천 출처가 가게마다 남고, 버전·진행률은 한 번만 갱신된다")
	void addStores_appendsInOrderWithRecommendation() {
		lockedCourse();
		given(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(COURSE_ID))
			.willReturn(List.of());
		Store first = store(100L);
		Store second = store(200L);
		given(storeRepository.findAllById(anyList())).willReturn(List.of(first, second));
		Recommendation recommendation = recommendationOf(COURSE_ID);
		given(recommendationRepository.findById(RECOMMENDATION_ID)).willReturn(Optional.of(recommendation));

		List<CourseStoreResponse> result = courseCommandService.addStores(MEMBER_ID, COURSE_ID,
			request(RECOMMENDATION_ID, 100L, 200L));

		assertThat(result).extracting(CourseStoreResponse::storeId).containsExactly(100L, 200L);
		assertThat(result).extracting(CourseStoreResponse::visitOrder).containsExactly(1, 2);
		assertThat(result).extracting(CourseStoreResponse::day).containsOnly(1);
		ArgumentCaptor<CourseStore> saved = ArgumentCaptor.forClass(CourseStore.class);
		then(courseStoreRepository).should(org.mockito.Mockito.times(2)).save(saved.capture());
		assertThat(saved.getAllValues()).allSatisfy(courseStore -> assertThat(courseStore.getRecommendationId())
			.isEqualTo(RECOMMENDATION_ID));
		then(courseAccessPolicy).should().requireEditable(course, MEMBER_ID);
		then(courseProgressService).should().refreshForCourse(COURSE_ID, true);
		then(courseRepository).should().bumpVersion(COURSE_ID);
	}

	@Test
	@DisplayName("추천 없이 담으면 출처가 비고, 이미 있는 정류장 뒤에 순서가 이어진다")
	void addStores_withoutRecommendation_appendsAfterExisting() {
		lockedCourse();
		CourseStore alreadyThere = existing(store(1L), 1, 1);
		given(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(COURSE_ID))
			.willReturn(List.of(alreadyThere));
		Store added = store(100L);
		given(storeRepository.findAllById(anyList())).willReturn(List.of(added));

		List<CourseStoreResponse> result = courseCommandService.addStores(MEMBER_ID, COURSE_ID, request(null, 100L));

		assertThat(result).hasSize(1);
		assertThat(result.get(0).visitOrder()).isEqualTo(2);
		ArgumentCaptor<CourseStore> saved = ArgumentCaptor.forClass(CourseStore.class);
		then(courseStoreRepository).should().save(saved.capture());
		assertThat(saved.getValue().getRecommendationId()).isNull();
		then(recommendationRepository).should(never()).findById(any());
	}

	@Test
	@DisplayName("같은 가게가 두 번 오거나 이미 담긴 가게가 섞이면 400 이고 버전은 안 오른다 — 트랜잭션이 전부 되돌린다")
	void addStores_duplicate_rejectsWhole() {
		lockedCourse();
		given(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(COURSE_ID))
			.willReturn(List.of());
		Store onlyOne = store(100L);
		given(storeRepository.findAllById(anyList())).willReturn(List.of(onlyOne));

		assertThatThrownBy(() -> courseCommandService.addStores(MEMBER_ID, COURSE_ID, request(null, 100L, 100L)))
			.isInstanceOfSatisfying(CourseException.class, e -> assertThat(e.getErrorCode())
				.isEqualTo(CourseErrorCode.COURSE_STORE_ALREADY_EXISTS));
		then(courseRepository).should(never()).bumpVersion(any());
		then(courseProgressService).should(never()).refreshForCourse(any(), org.mockito.ArgumentMatchers.anyBoolean());
	}

	@Test
	@DisplayName("다른 코스의 추천 id 면 아무것도 담지 않고 404 다")
	void addStores_recommendationOfOtherCourse_isNotFound() {
		lockedCourse();
		Recommendation otherCourses = recommendationOf(11L);
		given(recommendationRepository.findById(RECOMMENDATION_ID)).willReturn(Optional.of(otherCourses));

		assertThatThrownBy(() -> courseCommandService.addStores(MEMBER_ID, COURSE_ID,
			request(RECOMMENDATION_ID, 100L)))
			.isInstanceOfSatisfying(RecommendationException.class, e -> assertThat(e.getErrorCode())
				.isEqualTo(RecommendationErrorCode.RECOMMENDATION_NOT_FOUND));
		then(courseStoreRepository).should(never()).save(any());
	}

	@Test
	@DisplayName("요청한 가게가 하나라도 없으면 아무것도 담지 않는다")
	void addStores_unknownStore_isNotFound() {
		lockedCourse();
		given(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(COURSE_ID))
			.willReturn(List.of());
		Store found = store(100L);
		given(storeRepository.findAllById(anyList())).willReturn(List.of(found));

		assertThatThrownBy(() -> courseCommandService.addStores(MEMBER_ID, COURSE_ID, request(null, 100L, 999L)))
			.isInstanceOfSatisfying(StoreException.class, e -> assertThat(e.getErrorCode())
				.isEqualTo(StoreErrorCode.STORE_NOT_FOUND));
		then(courseStoreRepository).should(never()).save(any());
	}
}
