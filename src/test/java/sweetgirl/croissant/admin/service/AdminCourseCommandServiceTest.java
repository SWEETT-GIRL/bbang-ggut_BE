package sweetgirl.croissant.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
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
import sweetgirl.croissant.course.dto.request.AdminCourseCreateRequest;
import sweetgirl.croissant.course.dto.request.AdminCoursePinRequest;
import sweetgirl.croissant.course.dto.request.AdminCourseUpdateRequest;
import sweetgirl.croissant.course.dto.request.CourseImageRegisterRequest;
import sweetgirl.croissant.course.dto.request.CourseStoreAddRequest;
import sweetgirl.croissant.course.dto.request.CourseStoreReorderRequest;
import sweetgirl.croissant.course.dto.request.CourseStoreUpdateRequest;
import sweetgirl.croissant.course.dto.response.CourseStoreResponse;
import sweetgirl.croissant.course.entity.Course;
import sweetgirl.croissant.course.entity.CourseImageFile;
import sweetgirl.croissant.course.entity.CourseStore;
import sweetgirl.croissant.course.exception.CourseException;
import sweetgirl.croissant.course.repository.CourseImageFileRepository;
import sweetgirl.croissant.course.repository.CourseRepository;
import sweetgirl.croissant.course.repository.CourseStoreRepository;
import sweetgirl.croissant.external.aws.S3Service;
import sweetgirl.croissant.external.aws.dto.PresignedUrlResponse;
import sweetgirl.croissant.external.aws.service.ImageProcessingCallbackService;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.repository.StoreRepository;

@ExtendWith(MockitoExtension.class)
class AdminCourseCommandServiceTest {

	@Mock
	CourseRepository courseRepository;
	@Mock
	CourseStoreRepository courseStoreRepository;
	@Mock
	CourseImageFileRepository courseImageFileRepository;
	@Mock
	StoreRepository storeRepository;
	@Mock
	S3Service s3Service;
	@Mock
	ImageProcessingCallbackService imageProcessingCallbackService;

	@InjectMocks
	AdminCourseCommandService adminCourseCommandService;

	// ───────────────────────── createCuratedCourse ─────────────────────────

	@Test
	@DisplayName("createCuratedCourse: type=CURATED, member=null, isPublic=true 코스를 생성한다")
	void createCuratedCourse_success() {
		AdminCourseCreateRequest request = mock(AdminCourseCreateRequest.class);
		given(request.getTitle()).willReturn("망원동 빵지순례");
		given(request.getBadge()).willReturn("신규");

		Course savedCourse = mock(Course.class);
		given(savedCourse.getId()).willReturn(1L);
		given(courseRepository.save(any(Course.class))).willReturn(savedCourse);

		Long courseId = adminCourseCommandService.createCuratedCourse(request);

		assertThat(courseId).isEqualTo(1L);
	}

	// ───────────────────────── updateCuratedCourse ─────────────────────────

	@Test
	@DisplayName("updateCuratedCourse: title과 badge를 수정한다")
	void updateCuratedCourse_success() {
		Course course = mock(Course.class);
		given(courseRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(course));

		AdminCourseUpdateRequest request = mock(AdminCourseUpdateRequest.class);
		given(request.getTitle()).willReturn("수정된 제목");
		given(request.getBadge()).willReturn("수정된 뱃지");

		adminCourseCommandService.updateCuratedCourse(1L, request);

		then(course).should().updateTitle("수정된 제목");
		then(course).should().updateBadge("수정된 뱃지");
	}

	@Test
	@DisplayName("updateCuratedCourse: 없는 코스 수정 시 COURSE_NOT_FOUND를 던진다")
	void updateCuratedCourse_notFound_throws() {
		given(courseRepository.findByIdAndDeletedAtIsNull(99L)).willReturn(Optional.empty());
		AdminCourseUpdateRequest request = mock(AdminCourseUpdateRequest.class);

		assertThatThrownBy(() -> adminCourseCommandService.updateCuratedCourse(99L, request))
			.isInstanceOf(CourseException.class)
			.hasMessageContaining(CourseErrorCode.COURSE_NOT_FOUND.getMessage());
	}

	// ───────────────────────── deleteCuratedCourse ─────────────────────────

	@Test
	@DisplayName("deleteCuratedCourse: soft delete를 수행한다")
	void deleteCuratedCourse_success() {
		Course course = mock(Course.class);
		given(courseRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(course));

		adminCourseCommandService.deleteCuratedCourse(1L);

		then(course).should().softDelete();
	}

	@Test
	@DisplayName("deleteCuratedCourse: 없는 코스 삭제 시 COURSE_NOT_FOUND를 던진다")
	void deleteCuratedCourse_notFound_throws() {
		given(courseRepository.findByIdAndDeletedAtIsNull(99L)).willReturn(Optional.empty());

		assertThatThrownBy(() -> adminCourseCommandService.deleteCuratedCourse(99L))
			.isInstanceOf(CourseException.class)
			.hasMessageContaining(CourseErrorCode.COURSE_NOT_FOUND.getMessage());
	}

	// ───────────────────────── 이미지 관리 ─────────────────────────

	@Test
	@DisplayName("getPresignedUrl: presigned URL을 반환한다")
	void getPresignedUrl_success() {
		Course course = mock(Course.class);
		given(courseRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(course));
		PresignedUrlResponse expected = mock(PresignedUrlResponse.class);
		given(s3Service.generatePresignedUrl(any(), any())).willReturn(expected);

		PresignedUrlResponse result = adminCourseCommandService.getPresignedUrl(1L, "image/jpeg");

		assertThat(result).isEqualTo(expected);
	}

	@Test
	@DisplayName("registerImage: 기존 이미지가 있으면 S3에서 삭제 후 새 이미지를 저장한다")
	void registerImage_replacesExisting() {
		Course course = mock(Course.class);
		given(courseRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(course));

		CourseImageFile existing = mock(CourseImageFile.class);
		given(existing.getAwsKey()).willReturn("old-key");
		given(courseImageFileRepository.findByCourseId(1L)).willReturn(Optional.of(existing));

		CourseImageRegisterRequest request = mock(CourseImageRegisterRequest.class);
		given(request.getAwsKey()).willReturn("new-key");
		given(request.getMimeType()).willReturn("image/jpeg");
		given(request.getName()).willReturn("cover.jpg");

		adminCourseCommandService.registerImage(1L, request);

		then(s3Service).should().deleteObject("old-key");
		then(courseImageFileRepository).should().delete(existing);
		then(courseImageFileRepository).should().save(any(CourseImageFile.class));
	}

	@Test
	@DisplayName("deleteImage: 이미지가 없으면 COURSE_IMAGE_NOT_FOUND를 던진다")
	void deleteImage_notFound_throws() {
		Course course = mock(Course.class);
		given(courseRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(course));
		given(courseImageFileRepository.findByCourseId(1L)).willReturn(Optional.empty());

		assertThatThrownBy(() -> adminCourseCommandService.deleteImage(1L))
			.isInstanceOf(CourseException.class)
			.hasMessageContaining(CourseErrorCode.COURSE_IMAGE_NOT_FOUND.getMessage());
	}

	@Test
	@DisplayName("deleteImage: 이미지를 S3에서 삭제하고 DB에서도 제거한다")
	void deleteImage_success() {
		Course course = mock(Course.class);
		given(courseRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(course));

		CourseImageFile imageFile = mock(CourseImageFile.class);
		given(imageFile.getAwsKey()).willReturn("courses/1/cover.jpg");
		given(courseImageFileRepository.findByCourseId(1L)).willReturn(Optional.of(imageFile));

		adminCourseCommandService.deleteImage(1L);

		then(s3Service).should().deleteObject("courses/1/cover.jpg");
		then(courseImageFileRepository).should().delete(imageFile);
	}

	// ───────────────────────── 가게 관리 ─────────────────────────

	@Test
	@DisplayName("addStore: 가게를 추가하고 CourseStoreResponse를 반환한다")
	void addStore_success() {
		Course course = mock(Course.class);
		Store store = mockStore(10L);
		given(courseRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(course));
		given(storeRepository.findById(10L)).willReturn(Optional.of(store));
		given(courseStoreRepository.existsByCourseIdAndStoreIdAndDeletedAtIsNull(1L, 10L)).willReturn(false);
		given(courseStoreRepository.findMaxVisitOrderByCourseId(1L)).willReturn(0);

		CourseStoreAddRequest request = mock(CourseStoreAddRequest.class);
		given(request.getStoreId()).willReturn(10L);
		lenient().when(request.getMemo()).thenReturn(null);
		lenient().when(request.getVisitTime()).thenReturn(null);

		CourseStoreResponse result = adminCourseCommandService.addStore(1L, request);

		assertThat(result).isNotNull();
		assertThat(result.visitOrder()).isEqualTo(1);
	}

	@Test
	@DisplayName("addStore: 이미 추가된 가게면 COURSE_STORE_ALREADY_EXISTS를 던진다")
	void addStore_duplicate_throws() {
		Course course = mock(Course.class);
		given(courseRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(course));
		given(courseStoreRepository.existsByCourseIdAndStoreIdAndDeletedAtIsNull(1L, 10L)).willReturn(true);

		CourseStoreAddRequest request = mock(CourseStoreAddRequest.class);
		given(request.getStoreId()).willReturn(10L);

		assertThatThrownBy(() -> adminCourseCommandService.addStore(1L, request))
			.isInstanceOf(CourseException.class)
			.hasMessageContaining(CourseErrorCode.COURSE_STORE_ALREADY_EXISTS.getMessage());
	}

	@Test
	@DisplayName("updateStore: memo와 visitTime을 수정한다")
	void updateStore_success() {
		Course course = mock(Course.class);
		given(courseRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(course));
		CourseStore courseStore = mock(CourseStore.class);
		given(courseStoreRepository.findByCourseIdAndStoreIdAndDeletedAtIsNull(1L, 10L))
			.willReturn(Optional.of(courseStore));

		CourseStoreUpdateRequest request = mock(CourseStoreUpdateRequest.class);
		given(request.getMemo()).willReturn("창가 자리");
		given(request.getVisitTime()).willReturn(null);

		adminCourseCommandService.updateStore(1L, 10L, request);

		then(courseStore).should().updateMemo("창가 자리");
		then(courseStore).should().updateVisitTime(null);
	}

	@Test
	@DisplayName("removeStore: soft delete를 수행한다")
	void removeStore_success() {
		Course course = mock(Course.class);
		given(courseRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(course));
		CourseStore courseStore = mock(CourseStore.class);
		given(courseStoreRepository.findByCourseIdAndStoreIdAndDeletedAtIsNull(1L, 10L))
			.willReturn(Optional.of(courseStore));

		adminCourseCommandService.removeStore(1L, 10L);

		then(courseStore).should().softDelete();
	}

	@Test
	@DisplayName("reorderStores: courseStoreIds 순서로 visitOrder를 재배치한다")
	void reorderStores_success() {
		Course course = mock(Course.class);
		given(course.getId()).willReturn(1L);
		given(courseRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(course));

		CourseStore cs1 = mock(CourseStore.class);
		CourseStore cs2 = mock(CourseStore.class);
		given(cs1.getId()).willReturn(10L);
		given(cs1.getCourse()).willReturn(course);
		given(cs2.getId()).willReturn(20L);
		given(cs2.getCourse()).willReturn(course);
		given(courseStoreRepository.countByCourseIdAndDeletedAtIsNull(1L)).willReturn(2L);
		given(courseStoreRepository.findAllById(List.of(10L, 20L))).willReturn(List.of(cs1, cs2));

		CourseStoreReorderRequest.CourseStoreOrderItem item1 = mock(
			CourseStoreReorderRequest.CourseStoreOrderItem.class);
		CourseStoreReorderRequest.CourseStoreOrderItem item2 = mock(
			CourseStoreReorderRequest.CourseStoreOrderItem.class);
		given(item1.getCourseStoreId()).willReturn(10L);
		given(item2.getCourseStoreId()).willReturn(20L);

		CourseStoreReorderRequest request = mock(CourseStoreReorderRequest.class);
		given(request.getCourseStoreOrders()).willReturn(List.of(item1, item2));

		adminCourseCommandService.reorderStores(1L, request);

		// 유니크 제약 충돌 방지를 위한 임시 음수 순서 → 최종 순서 2단계 갱신
		then(cs1).should().updateOrder(-1);
		then(cs1).should().updateOrder(1);
		then(cs2).should().updateOrder(-2);
		then(cs2).should().updateOrder(2);
		then(courseStoreRepository).should().flush();
	}

	@Test
	@DisplayName("reorderStores: 일부 가게만 전달하면 COURSE_STORE_ORDER_INVALID를 던진다")
	void reorderStores_incompleteCourseStores_throwsInvalidOrder() {
		Course course = mock(Course.class);
		given(courseRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(course));
		CourseStoreReorderRequest.CourseStoreOrderItem item = mock(
			CourseStoreReorderRequest.CourseStoreOrderItem.class);
		given(item.getCourseStoreId()).willReturn(10L);
		CourseStoreReorderRequest request = mock(CourseStoreReorderRequest.class);
		given(request.getCourseStoreOrders()).willReturn(List.of(item));
		given(courseStoreRepository.countByCourseIdAndDeletedAtIsNull(1L)).willReturn(2L);

		assertThatThrownBy(() -> adminCourseCommandService.reorderStores(1L, request))
			.isInstanceOf(CourseException.class)
			.hasMessageContaining(CourseErrorCode.COURSE_STORE_ORDER_INVALID.getMessage());
	}

	@Test
	@DisplayName("reorderStores: 다른 코스의 가게가 섞여 있으면 COURSE_STORE_NOT_FOUND를 던진다")
	void reorderStores_otherCourseStore_throwsNotFound() {
		Course course = mock(Course.class);
		given(courseRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(course));

		Course otherCourse = mock(Course.class);
		given(otherCourse.getId()).willReturn(99L);
		CourseStore otherCourseStore = mock(CourseStore.class);
		given(otherCourseStore.getId()).willReturn(77L);
		given(otherCourseStore.getCourse()).willReturn(otherCourse);
		given(courseStoreRepository.countByCourseIdAndDeletedAtIsNull(1L)).willReturn(1L);
		given(courseStoreRepository.findAllById(List.of(77L))).willReturn(List.of(otherCourseStore));

		CourseStoreReorderRequest.CourseStoreOrderItem item = mock(
			CourseStoreReorderRequest.CourseStoreOrderItem.class);
		given(item.getCourseStoreId()).willReturn(77L);
		CourseStoreReorderRequest request = mock(CourseStoreReorderRequest.class);
		given(request.getCourseStoreOrders()).willReturn(List.of(item));

		assertThatThrownBy(() -> adminCourseCommandService.reorderStores(1L, request))
			.isInstanceOf(CourseException.class)
			.hasMessageContaining(CourseErrorCode.COURSE_STORE_NOT_FOUND.getMessage());
	}

	// ───────────────────────── 핀 관리 ─────────────────────────

	@Test
	@DisplayName("pinCourse: 코스를 지정 순위에 핀한다")
	void pinCourse_success() {
		Course course = mock(Course.class);
		given(course.isPinned()).willReturn(false);
		given(courseRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(course));
		given(courseRepository.countByPinOrderIsNotNullAndDeletedAtIsNull()).willReturn(1L);
		given(courseRepository.findByPinOrderAndDeletedAtIsNull(2)).willReturn(Optional.empty());

		AdminCoursePinRequest request = mock(AdminCoursePinRequest.class);
		given(request.getPinOrder()).willReturn(2);

		adminCourseCommandService.pinCourse(1L, request);

		then(course).should().pin(2);
	}

	@Test
	@DisplayName("pinCourse: 이미 핀된 코스면 COURSE_ALREADY_PINNED를 던진다")
	void pinCourse_alreadyPinned_throws() {
		Course course = mock(Course.class);
		given(course.isPinned()).willReturn(true);
		given(courseRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(course));

		assertThatThrownBy(() -> adminCourseCommandService.pinCourse(1L, mock(AdminCoursePinRequest.class)))
			.isInstanceOf(CourseException.class)
			.hasMessageContaining(CourseErrorCode.COURSE_ALREADY_PINNED.getMessage());
	}

	@Test
	@DisplayName("pinCourse: 핀이 3개면 COURSE_PIN_LIMIT_EXCEEDED를 던진다")
	void pinCourse_limitExceeded_throws() {
		Course course = mock(Course.class);
		given(course.isPinned()).willReturn(false);
		given(courseRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(course));
		given(courseRepository.countByPinOrderIsNotNullAndDeletedAtIsNull()).willReturn(3L);

		assertThatThrownBy(() -> adminCourseCommandService.pinCourse(1L, mock(AdminCoursePinRequest.class)))
			.isInstanceOf(CourseException.class)
			.hasMessageContaining(CourseErrorCode.COURSE_PIN_LIMIT_EXCEEDED.getMessage());
	}

	@Test
	@DisplayName("pinCourse: 해당 순위가 이미 사용 중이면 COURSE_PIN_ORDER_CONFLICT를 던진다")
	void pinCourse_orderConflict_throws() {
		Course course = mock(Course.class);
		given(course.isPinned()).willReturn(false);
		given(courseRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(course));
		given(courseRepository.countByPinOrderIsNotNullAndDeletedAtIsNull()).willReturn(1L);

		Course existingPinned = mock(Course.class);
		given(courseRepository.findByPinOrderAndDeletedAtIsNull(1)).willReturn(Optional.of(existingPinned));

		AdminCoursePinRequest request = mock(AdminCoursePinRequest.class);
		given(request.getPinOrder()).willReturn(1);

		assertThatThrownBy(() -> adminCourseCommandService.pinCourse(1L, request))
			.isInstanceOf(CourseException.class)
			.hasMessageContaining(CourseErrorCode.COURSE_PIN_ORDER_CONFLICT.getMessage());
	}

	@Test
	@DisplayName("unpinCourse: 핀을 해제한다")
	void unpinCourse_success() {
		Course course = mock(Course.class);
		given(course.isPinned()).willReturn(true);
		given(courseRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(course));

		adminCourseCommandService.unpinCourse(1L);

		then(course).should().unpin();
	}

	@Test
	@DisplayName("unpinCourse: 핀되지 않은 코스면 COURSE_NOT_PINNED를 던진다")
	void unpinCourse_notPinned_throws() {
		Course course = mock(Course.class);
		given(course.isPinned()).willReturn(false);
		given(courseRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(course));

		assertThatThrownBy(() -> adminCourseCommandService.unpinCourse(1L))
			.isInstanceOf(CourseException.class)
			.hasMessageContaining(CourseErrorCode.COURSE_NOT_PINNED.getMessage());
	}

	// ───────────────────────── helpers ─────────────────────────

	private Store mockStore(Long id) {
		Store store = mock(Store.class);
		Point location = mock(Point.class);
		given(store.getId()).willReturn(id);
		given(store.getName()).willReturn("빵집");
		given(store.getRegion2nd()).willReturn("마포구");
		given(store.getLocation()).willReturn(location);
		given(location.getY()).willReturn(37.565);
		given(location.getX()).willReturn(126.977);
		return store;
	}
}
