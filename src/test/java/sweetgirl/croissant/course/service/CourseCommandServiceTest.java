package sweetgirl.croissant.course.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import java.time.LocalTime;
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
import sweetgirl.croissant.course.dto.request.CourseCreateRequest;
import sweetgirl.croissant.course.dto.request.CourseStoreAddRequest;
import sweetgirl.croissant.course.dto.request.CourseStoreReorderRequest;
import sweetgirl.croissant.course.dto.request.CourseStoreUpdateRequest;
import sweetgirl.croissant.course.dto.request.CourseUpdateRequest;
import sweetgirl.croissant.course.dto.response.CourseStoreResponse;
import sweetgirl.croissant.course.entity.Course;
import sweetgirl.croissant.course.entity.CourseImageFile;
import sweetgirl.croissant.course.entity.CourseStore;
import sweetgirl.croissant.course.enums.CourseType;
import sweetgirl.croissant.course.exception.CourseException;
import sweetgirl.croissant.course.repository.CourseImageFileRepository;
import sweetgirl.croissant.course.repository.CourseRepository;
import sweetgirl.croissant.course.repository.CourseStoreRepository;
import sweetgirl.croissant.course.repository.SavedCourseRepository;
import sweetgirl.croissant.external.aws.S3Service;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.repository.MemberRepository;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.repository.StoreRepository;

@ExtendWith(MockitoExtension.class)
class CourseCommandServiceTest {

	@Mock
	CourseRepository courseRepository;
	@Mock
	CourseStoreRepository courseStoreRepository;
	@Mock
	CourseImageFileRepository courseImageFileRepository;
	@Mock
	SavedCourseRepository savedCourseRepository;
	@Mock
	MemberRepository memberRepository;
	@Mock
	StoreRepository storeRepository;
	@Mock
	S3Service s3Service;

	@InjectMocks
	CourseCommandService courseCommandService;

	// ───────────────────────── createCourse ─────────────────────────

	@Test
	@DisplayName("createCourse: 코스를 생성하고 ID를 반환한다")
	void createCourse_success() {
		Long memberId = 1L;
		Member member = mockMember(memberId);
		CourseCreateRequest request = mock(CourseCreateRequest.class);
		lenient().when(request.getTitle()).thenReturn("나의 빵지순례");
		lenient().when(request.isPublic()).thenReturn(false);

		given(memberRepository.findByIdAndDeletedAtIsNull(memberId)).willReturn(Optional.of(member));
		Course savedCourse = mock(Course.class);
		given(savedCourse.getId()).willReturn(10L);
		given(courseRepository.save(any(Course.class))).willReturn(savedCourse);

		Long courseId = courseCommandService.createCourse(memberId, request);

		assertThat(courseId).isEqualTo(10L);
		then(courseRepository).should().save(any(Course.class));
	}

	// ───────────────────────── updateCourse ─────────────────────────

	@Test
	@DisplayName("updateCourse: 소유자가 수정하면 성공한다")
	void updateCourse_owner_success() {
		Long memberId = 1L;
		Long courseId = 10L;
		Course course = mockCourse(courseId, "원래 제목", CourseType.CUSTOM, memberId, null);
		CourseUpdateRequest request = mock(CourseUpdateRequest.class);
		given(request.getTitle()).willReturn("수정된 제목");
		given(request.getIsPublic()).willReturn(true);

		given(courseRepository.findByIdAndDeletedAtIsNull(courseId)).willReturn(Optional.of(course));

		courseCommandService.updateCourse(memberId, courseId, request);

		then(course).should().updateTitle("수정된 제목");
		then(course).should().updatePublic(true);
	}

	@Test
	@DisplayName("updateCourse: 타인이 수정하면 COURSE_FORBIDDEN을 던진다")
	void updateCourse_otherMember_throwsForbidden() {
		Long ownerId = 1L;
		Long otherId = 99L;
		Long courseId = 10L;
		Course course = mockCourse(courseId, "제목", CourseType.CUSTOM, ownerId, null);
		CourseUpdateRequest request = mock(CourseUpdateRequest.class);

		given(courseRepository.findByIdAndDeletedAtIsNull(courseId)).willReturn(Optional.of(course));

		assertThatThrownBy(() -> courseCommandService.updateCourse(otherId, courseId, request))
			.isInstanceOf(CourseException.class)
			.hasMessageContaining(CourseErrorCode.COURSE_FORBIDDEN.getMessage());
	}

	@Test
	@DisplayName("updateCourse: CURATED 코스 수정 시도 시 COURSE_FORBIDDEN을 던진다")
	void updateCourse_curatedCourse_throwsForbidden() {
		Long memberId = 1L;
		Long courseId = 10L;
		Course course = mockCourse(courseId, "큐레이션 코스", CourseType.CURATED, memberId, null);
		CourseUpdateRequest request = mock(CourseUpdateRequest.class);

		given(courseRepository.findByIdAndDeletedAtIsNull(courseId)).willReturn(Optional.of(course));

		assertThatThrownBy(() -> courseCommandService.updateCourse(memberId, courseId, request))
			.isInstanceOf(CourseException.class)
			.hasMessageContaining(CourseErrorCode.COURSE_FORBIDDEN.getMessage());
	}

	@Test
	@DisplayName("updateCourse: FORKED 코스이고 원본과 동일하면 COURSE_FORK_NOT_MODIFIED를 던진다")
	void updateCourse_forkedSameAsSource_throwsForkNotModified() {
		Long memberId = 1L;
		Long courseId = 10L;
		Long sourceCourseId = 5L;
		String sourceTitle = "원본 제목";

		Course course = mockCourse(courseId, "copy of 원본 제목", CourseType.FORKED, memberId, sourceCourseId);
		Course sourceCourse = mockCourse(sourceCourseId, sourceTitle, CourseType.CURATED, null, null);

		CourseUpdateRequest request = mock(CourseUpdateRequest.class);
		given(request.getTitle()).willReturn(sourceTitle);
		lenient().when(request.getIsPublic()).thenReturn(null);

		given(courseRepository.findByIdAndDeletedAtIsNull(courseId)).willReturn(Optional.of(course));
		given(courseRepository.findByIdAndDeletedAtIsNull(sourceCourseId)).willReturn(Optional.of(sourceCourse));
		given(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(courseId))
			.willReturn(List.of());
		given(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(sourceCourseId))
			.willReturn(List.of());

		assertThatThrownBy(() -> courseCommandService.updateCourse(memberId, courseId, request))
			.isInstanceOf(CourseException.class)
			.hasMessageContaining(CourseErrorCode.COURSE_FORK_NOT_MODIFIED.getMessage());
	}

	// ───────────────────────── deleteCourse ─────────────────────────

	@Test
	@DisplayName("deleteCourse: 소유자가 삭제하면 소프트 딜리트된다")
	void deleteCourse_success() {
		Long memberId = 1L;
		Long courseId = 10L;
		Course course = mockCourse(courseId, "삭제할 코스", CourseType.CUSTOM, memberId, null);

		given(courseRepository.findByIdAndDeletedAtIsNull(courseId)).willReturn(Optional.of(course));

		courseCommandService.deleteCourse(memberId, courseId);

		then(course).should().softDelete();
	}

	// ───────────────────────── addStore ─────────────────────────

	@Test
	@DisplayName("addStore: 가게를 코스에 추가하면 CourseStoreResponse를 반환한다")
	void addStore_success() {
		Long memberId = 1L;
		Long courseId = 10L;
		Long storeId = 100L;

		Course course = mockCourse(courseId, "코스", CourseType.CUSTOM, memberId, null);
		Store store = mockStore(storeId);
		CourseStoreAddRequest request = mock(CourseStoreAddRequest.class);
		given(request.getStoreId()).willReturn(storeId);
		given(request.getMemo()).willReturn("메모");
		given(request.getVisitTime()).willReturn(LocalTime.of(10, 0));
		given(request.getWalkingMinutesToNext()).willReturn(5);

		given(courseRepository.findByIdAndDeletedAtIsNull(courseId)).willReturn(Optional.of(course));
		given(courseStoreRepository.existsByCourseIdAndStoreIdAndDeletedAtIsNull(courseId, storeId)).willReturn(false);
		given(storeRepository.findById(storeId)).willReturn(Optional.of(store));
		given(courseStoreRepository.findMaxVisitOrderByCourseId(courseId)).willReturn(0);

		CourseStore savedCourseStore = mockCourseStore(1, store);
		given(courseStoreRepository.save(any(CourseStore.class))).willReturn(savedCourseStore);

		CourseStoreResponse result = courseCommandService.addStore(memberId, courseId, request);

		assertThat(result).isNotNull();
		then(courseStoreRepository).should().save(any(CourseStore.class));
	}

	@Test
	@DisplayName("addStore: 이미 추가된 가게이면 COURSE_STORE_ALREADY_EXISTS를 던진다")
	void addStore_duplicate_throwsAlreadyExists() {
		Long memberId = 1L;
		Long courseId = 10L;
		Long storeId = 100L;

		Course course = mockCourse(courseId, "코스", CourseType.CUSTOM, memberId, null);
		CourseStoreAddRequest request = mock(CourseStoreAddRequest.class);
		given(request.getStoreId()).willReturn(storeId);

		given(courseRepository.findByIdAndDeletedAtIsNull(courseId)).willReturn(Optional.of(course));
		given(courseStoreRepository.existsByCourseIdAndStoreIdAndDeletedAtIsNull(courseId, storeId)).willReturn(true);

		assertThatThrownBy(() -> courseCommandService.addStore(memberId, courseId, request))
			.isInstanceOf(CourseException.class)
			.hasMessageContaining(CourseErrorCode.COURSE_STORE_ALREADY_EXISTS.getMessage());
	}

	// ───────────────────────── updateStore ─────────────────────────

	@Test
	@DisplayName("updateStore: 메모와 방문 시간을 수정한다")
	void updateStore_success() {
		Long memberId = 1L;
		Long courseId = 10L;
		Long storeId = 100L;

		Course course = mockCourse(courseId, "코스", CourseType.CUSTOM, memberId, null);
		CourseStore courseStore = mock(CourseStore.class);
		CourseStoreUpdateRequest request = mock(CourseStoreUpdateRequest.class);
		given(request.getMemo()).willReturn("새 메모");
		given(request.getVisitTime()).willReturn(LocalTime.of(14, 30));

		given(courseRepository.findByIdAndDeletedAtIsNull(courseId)).willReturn(Optional.of(course));
		given(courseStoreRepository.findByCourseIdAndStoreIdAndDeletedAtIsNull(courseId, storeId))
			.willReturn(Optional.of(courseStore));

		courseCommandService.updateStore(memberId, courseId, storeId, request);

		then(courseStore).should().updateMemo("새 메모");
		then(courseStore).should().updateVisitTime(LocalTime.of(14, 30));
	}

	// ───────────────────────── removeStore ─────────────────────────

	@Test
	@DisplayName("removeStore: 가게를 소프트 딜리트한다")
	void removeStore_success() {
		Long memberId = 1L;
		Long courseId = 10L;
		Long storeId = 100L;

		Course course = mockCourse(courseId, "코스", CourseType.CUSTOM, memberId, null);
		CourseStore courseStore = mock(CourseStore.class);

		given(courseRepository.findByIdAndDeletedAtIsNull(courseId)).willReturn(Optional.of(course));
		given(courseStoreRepository.findByCourseIdAndStoreIdAndDeletedAtIsNull(courseId, storeId))
			.willReturn(Optional.of(courseStore));

		courseCommandService.removeStore(memberId, courseId, storeId);

		then(courseStore).should().softDelete();
	}

	// ───────────────────────── reorderStores ─────────────────────────

	@Test
	@DisplayName("reorderStores: 가게 순서와 도보 시간을 일괄 갱신한다")
	void reorderStores_success() {
		Long memberId = 1L;
		Long courseId = 10L;
		Long courseStoreId1 = 1L;
		Long courseStoreId2 = 2L;

		Course course = mockCourse(courseId, "코스", CourseType.CUSTOM, memberId, null);

		CourseStoreReorderRequest.CourseStoreOrderItem item1 = mock(
			CourseStoreReorderRequest.CourseStoreOrderItem.class);
		given(item1.getCourseStoreId()).willReturn(courseStoreId1);
		given(item1.getWalkingMinutesToNext()).willReturn(10);

		CourseStoreReorderRequest.CourseStoreOrderItem item2 = mock(
			CourseStoreReorderRequest.CourseStoreOrderItem.class);
		given(item2.getCourseStoreId()).willReturn(courseStoreId2);
		given(item2.getWalkingMinutesToNext()).willReturn(null);

		CourseStoreReorderRequest request = mock(CourseStoreReorderRequest.class);
		given(request.getCourseStoreOrders()).willReturn(List.of(item1, item2));

		CourseStore courseStore1 = mock(CourseStore.class);
		given(courseStore1.getId()).willReturn(courseStoreId1);
		CourseStore courseStore2 = mock(CourseStore.class);
		given(courseStore2.getId()).willReturn(courseStoreId2);

		given(courseRepository.findByIdAndDeletedAtIsNull(courseId)).willReturn(Optional.of(course));
		given(courseStoreRepository.findAllById(List.of(courseStoreId1, courseStoreId2)))
			.willReturn(List.of(courseStore1, courseStore2));

		courseCommandService.reorderStores(memberId, courseId, request);

		then(courseStore1).should().updateOrder(1);
		then(courseStore1).should().updateWalkingMinutesToNext(10);
		then(courseStore2).should().updateOrder(2);
		then(courseStore2).should().updateWalkingMinutesToNext(null);
	}

	// ───────────────────────── image ─────────────────────────

	@Test
	@DisplayName("getPresignedUrl: presigned URL을 발급한다")
	void getPresignedUrl_success() {
		Long memberId = 1L;
		Long courseId = 10L;
		Course course = mockCourse(courseId, "코스", CourseType.CUSTOM, memberId, null);

		given(courseRepository.findByIdAndDeletedAtIsNull(courseId)).willReturn(Optional.of(course));
		given(s3Service.generatePresignedUrl(any(), eq("image/jpeg"))).willReturn(
			new sweetgirl.croissant.external.aws.dto.PresignedUrlResponse(
				"file.jpg", "image/jpeg", "https://s3.example.com/presigned", "courses/10/file.jpg"));

		var result = courseCommandService.getPresignedUrl(memberId, courseId, "image/jpeg");

		assertThat(result.uploadUrl()).contains("https://s3.example.com/presigned");
	}

	@Test
	@DisplayName("registerImage: 코스 이미지를 등록한다")
	void registerImage_success() {
		Long memberId = 1L;
		Long courseId = 10L;
		Course course = mockCourse(courseId, "코스", CourseType.CUSTOM, memberId, null);
		sweetgirl.croissant.course.dto.request.CourseImageRegisterRequest request = mock(
			sweetgirl.croissant.course.dto.request.CourseImageRegisterRequest.class);
		given(request.getAwsKey()).willReturn("courses/10/abc.jpg");
		given(request.getMimeType()).willReturn("image/jpeg");
		given(request.getName()).willReturn("abc.jpg");

		given(courseRepository.findByIdAndDeletedAtIsNull(courseId)).willReturn(Optional.of(course));
		given(courseImageFileRepository.findByCourseId(courseId)).willReturn(Optional.empty());

		courseCommandService.registerImage(memberId, courseId, request);

		then(courseImageFileRepository).should().save(any(CourseImageFile.class));
	}

	@Test
	@DisplayName("deleteImage: 코스 이미지를 삭제한다")
	void deleteImage_success() {
		Long memberId = 1L;
		Long courseId = 10L;
		Course course = mockCourse(courseId, "코스", CourseType.CUSTOM, memberId, null);
		CourseImageFile imageFile = mock(CourseImageFile.class);
		given(imageFile.getAwsKey()).willReturn("courses/10/abc.jpg");

		given(courseRepository.findByIdAndDeletedAtIsNull(courseId)).willReturn(Optional.of(course));
		given(courseImageFileRepository.findByCourseId(courseId)).willReturn(Optional.of(imageFile));

		courseCommandService.deleteImage(memberId, courseId);

		then(s3Service).should().deleteObject("courses/10/abc.jpg");
		then(courseImageFileRepository).should().delete(imageFile);
	}

	@Test
	@DisplayName("deleteImage: 이미지가 없으면 COURSE_IMAGE_NOT_FOUND를 던진다")
	void deleteImage_notFound_throwsImageNotFound() {
		Long memberId = 1L;
		Long courseId = 10L;
		Course course = mockCourse(courseId, "코스", CourseType.CUSTOM, memberId, null);

		given(courseRepository.findByIdAndDeletedAtIsNull(courseId)).willReturn(Optional.of(course));
		given(courseImageFileRepository.findByCourseId(courseId)).willReturn(Optional.empty());

		assertThatThrownBy(() -> courseCommandService.deleteImage(memberId, courseId))
			.isInstanceOf(CourseException.class)
			.hasMessageContaining(CourseErrorCode.COURSE_IMAGE_NOT_FOUND.getMessage());

		then(s3Service).should(never()).deleteObject(any());
	}

	// ───────────────────────── forkCourse ─────────────────────────

	@Test
	@DisplayName("forkCourse: CURATED 코스를 FORKED로 포크하면 새 코스 ID를 반환한다")
	void forkCourse_curated_success() {
		Long memberId = 1L;
		Long sourceId = 5L;

		Course sourceCourse = mockCourse(sourceId, "큐레이션 코스", CourseType.CURATED, null, null);
		Member member = mockMember(memberId);
		CourseStore sourceStore = mockCourseStore(1, mockStore(100L));

		given(courseRepository.findByIdAndDeletedAtIsNull(sourceId)).willReturn(Optional.of(sourceCourse));
		given(memberRepository.findByIdAndDeletedAtIsNull(memberId)).willReturn(Optional.of(member));
		given(courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(sourceId))
			.willReturn(List.of(sourceStore));

		Course forkedCourse = mock(Course.class);
		given(forkedCourse.getId()).willReturn(20L);
		given(courseRepository.save(any(Course.class))).willReturn(forkedCourse);

		Long forkedId = courseCommandService.forkCourse(memberId, sourceId);

		assertThat(forkedId).isEqualTo(20L);
		then(courseStoreRepository).should().saveAll(any());
	}

	@Test
	@DisplayName("forkCourse: CUSTOM 코스 포크 시도 시 COURSE_FORK_NOT_CURATED를 던진다")
	void forkCourse_custom_throwsForkNotCurated() {
		Long memberId = 1L;
		Long courseId = 10L;
		Course course = mockCourse(courseId, "커스텀 코스", CourseType.CUSTOM, memberId, null);

		given(courseRepository.findByIdAndDeletedAtIsNull(courseId)).willReturn(Optional.of(course));

		assertThatThrownBy(() -> courseCommandService.forkCourse(memberId, courseId))
			.isInstanceOf(CourseException.class)
			.hasMessageContaining(CourseErrorCode.COURSE_FORK_NOT_CURATED.getMessage());
	}

	@Test
	@DisplayName("forkCourse: FORKED 코스 포크 시도 시 COURSE_FORK_NOT_CURATED를 던진다")
	void forkCourse_forked_throwsForkNotCurated() {
		Long memberId = 1L;
		Long courseId = 10L;
		Course course = mockCourse(courseId, "포크 코스", CourseType.FORKED, memberId, 5L);

		given(courseRepository.findByIdAndDeletedAtIsNull(courseId)).willReturn(Optional.of(course));

		assertThatThrownBy(() -> courseCommandService.forkCourse(memberId, courseId))
			.isInstanceOf(CourseException.class)
			.hasMessageContaining(CourseErrorCode.COURSE_FORK_NOT_CURATED.getMessage());
	}

	// ───────────────────────── helpers ─────────────────────────

	private Member mockMember(Long memberId) {
		Member member = mock(Member.class);
		lenient().when(member.getId()).thenReturn(memberId);
		return member;
	}

	private Course mockCourse(Long id, String title, CourseType type, Long memberId, Long sourceCourseId) {
		Course course = mock(Course.class);
		lenient().when(course.getId()).thenReturn(id);
		lenient().when(course.getTitle()).thenReturn(title);
		lenient().when(course.getType()).thenReturn(type);
		lenient().when(course.getSourceCourseId()).thenReturn(sourceCourseId);
		if (memberId != null) {
			Member member = mockMember(memberId);
			lenient().when(course.getMember()).thenReturn(member);
		} else {
			lenient().when(course.getMember()).thenReturn(null);
		}
		return course;
	}

	private Store mockStore(Long storeId) {
		Store store = mock(Store.class);
		lenient().when(store.getId()).thenReturn(storeId);
		Point location = mock(Point.class);
		lenient().when(store.getLocation()).thenReturn(location);
		lenient().when(location.getY()).thenReturn(37.5);
		lenient().when(location.getX()).thenReturn(126.9);
		lenient().when(store.getName()).thenReturn("테스트 빵집");
		lenient().when(store.getRegion2nd()).thenReturn("마포구");
		return store;
	}

	private CourseStore mockCourseStore(int visitOrder, Store store) {
		CourseStore courseStore = mock(CourseStore.class);
		lenient().when(courseStore.getStore()).thenReturn(store);
		lenient().when(courseStore.getVisitOrder()).thenReturn(visitOrder);
		lenient().when(courseStore.getMemo()).thenReturn(null);
		lenient().when(courseStore.getVisitTime()).thenReturn(null);
		lenient().when(courseStore.getWalkingMinutesToNext()).thenReturn(null);
		return courseStore;
	}
}
