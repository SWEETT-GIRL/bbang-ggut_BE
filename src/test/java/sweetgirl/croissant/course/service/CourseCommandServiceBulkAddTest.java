package sweetgirl.croissant.course.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
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
import org.springframework.context.ApplicationEventPublisher;

import sweetgirl.croissant.course.code.CourseErrorCode;
import sweetgirl.croissant.course.dto.request.CourseStoreBulkItem;
import sweetgirl.croissant.course.dto.response.CourseStoreResponse;
import sweetgirl.croissant.course.entity.Course;
import sweetgirl.croissant.course.entity.CourseStore;
import sweetgirl.croissant.course.event.CourseStoreAddedEvent;
import sweetgirl.croissant.course.exception.CourseException;
import sweetgirl.croissant.course.repository.CourseRepository;
import sweetgirl.croissant.course.repository.CourseStoreRepository;
import sweetgirl.croissant.member.code.MemberErrorCode;
import sweetgirl.croissant.member.exception.MemberException;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.store.code.StoreErrorCode;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.exception.StoreException;
import sweetgirl.croissant.store.repository.StoreRepository;

/**
 * bulk 담기(#660)만 본다 — 단건 담기의 나머지 규칙은 CourseCommandServiceTest 가,
 * 추천 소유 확인은 CourseRecommendationCommandServiceTest 가 본다.
 */
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
	private ApplicationEventPublisher eventPublisher;
	@Mock
	private MemberReader memberReader;

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

	private static List<CourseStoreBulkItem> items(Long... storeIds) {
		return java.util.Arrays.stream(storeIds).map(id -> new CourseStoreBulkItem(id, null)).toList();
	}

	@Test
	@DisplayName("게스트는 한꺼번에 담기도 막힌다 — 단건 담기와 같은 문")
	void addStores_rejectsGuest() {
		willThrow(new MemberException(MemberErrorCode.GUEST_LOGIN_REQUIRED))
			.given(memberReader).requireLoggedIn(MEMBER_ID);

		assertThatThrownBy(() -> courseCommandService.addStores(MEMBER_ID, COURSE_ID, items(100L), null))
			.isInstanceOf(MemberException.class);
		then(courseRepository).should(never()).findWithLockByIdAndDeletedAtIsNull(any());
	}

	@Test
	@DisplayName("빈 코스에 두 곳을 담으면 순서 1·2 로 이어지고, 넘겨받은 추천 id 가 가게마다 출처로 남고, 버전·진행률은 한 번만 갱신된다")
	void addStores_appendsInOrderWithRecommendation() {
		lockedCourse();
		given(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(COURSE_ID))
			.willReturn(List.of());
		Store first = store(100L);
		Store second = store(200L);
		given(storeRepository.findAllById(anyList())).willReturn(List.of(first, second));
		List<CourseStoreResponse> result = courseCommandService.addStores(MEMBER_ID, COURSE_ID, items(100L, 200L),
			RECOMMENDATION_ID);

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
		// 담은 사람 기준 COURSE_ADD 신호 — 가게마다 하나, 추천 출처를 실어(#663)
		ArgumentCaptor<CourseStoreAddedEvent> events = ArgumentCaptor.forClass(CourseStoreAddedEvent.class);
		then(eventPublisher).should(org.mockito.Mockito.times(2)).publishEvent(events.capture());
		assertThat(events.getAllValues()).extracting(CourseStoreAddedEvent::storeId).containsExactly(100L, 200L);
		assertThat(events.getAllValues()).allSatisfy(event -> {
			assertThat(event.memberId()).isEqualTo(MEMBER_ID);
			assertThat(event.courseId()).isEqualTo(COURSE_ID);
			assertThat(event.recommendationId()).isEqualTo(RECOMMENDATION_ID);
			assertThat(event.addedAt()).isNotNull();
		});
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

		List<CourseStoreResponse> result = courseCommandService.addStores(MEMBER_ID, COURSE_ID, items(100L), null);

		assertThat(result).hasSize(1);
		assertThat(result.get(0).visitOrder()).isEqualTo(2);
		ArgumentCaptor<CourseStore> saved = ArgumentCaptor.forClass(CourseStore.class);
		then(courseStoreRepository).should().save(saved.capture());
		assertThat(saved.getValue().getRecommendationId()).isNull();
	}

	@Test
	@DisplayName("같은 가게가 두 번 오거나 이미 담긴 가게가 섞이면 400 이고 버전은 안 오른다 — 트랜잭션이 전부 되돌린다")
	void addStores_duplicate_rejectsWhole() {
		lockedCourse();
		given(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(COURSE_ID))
			.willReturn(List.of());
		Store onlyOne = store(100L);
		given(storeRepository.findAllById(anyList())).willReturn(List.of(onlyOne));

		assertThatThrownBy(() -> courseCommandService.addStores(MEMBER_ID, COURSE_ID, items(100L, 100L), null))
			.isInstanceOfSatisfying(CourseException.class, e -> assertThat(e.getErrorCode())
				.isEqualTo(CourseErrorCode.COURSE_STORE_ALREADY_EXISTS));
		then(courseRepository).should(never()).bumpVersion(any());
		then(courseProgressService).should(never()).refreshForCourse(any(), org.mockito.ArgumentMatchers.anyBoolean());
	}

	@Test
	@DisplayName("요청한 가게가 하나라도 없으면 아무것도 담지 않는다")
	void addStores_unknownStore_isNotFound() {
		lockedCourse();
		given(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(COURSE_ID))
			.willReturn(List.of());
		Store found = store(100L);
		given(storeRepository.findAllById(anyList())).willReturn(List.of(found));

		assertThatThrownBy(() -> courseCommandService.addStores(MEMBER_ID, COURSE_ID, items(100L, 999L), null))
			.isInstanceOfSatisfying(StoreException.class, e -> assertThat(e.getErrorCode())
				.isEqualTo(StoreErrorCode.STORE_NOT_FOUND));
		then(courseStoreRepository).should(never()).save(any());
	}
}
