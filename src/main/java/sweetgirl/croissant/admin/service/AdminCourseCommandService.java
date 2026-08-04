package sweetgirl.croissant.admin.service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.common.enums.MimeType;
import sweetgirl.croissant.course.code.CourseErrorCode;
import sweetgirl.croissant.course.dto.request.AdminCourseCreateRequest;
import sweetgirl.croissant.course.dto.request.AdminCoursePinRequest;
import sweetgirl.croissant.course.dto.request.AdminCourseUpdateRequest;
import sweetgirl.croissant.course.dto.request.CourseImageRegisterRequest;
import sweetgirl.croissant.course.dto.request.CourseStoreAddRequest;
import sweetgirl.croissant.course.dto.request.CourseStoreReorderRequest;
import sweetgirl.croissant.course.dto.request.CourseStoreReorderRequest.CourseStoreOrderItem;
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
import sweetgirl.croissant.external.aws.util.S3PathGenerator;
import sweetgirl.croissant.store.code.StoreErrorCode;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.exception.StoreException;
import sweetgirl.croissant.store.repository.StoreRepository;

@Service
@RequiredArgsConstructor
@Transactional
public class AdminCourseCommandService {

	private final CourseRepository courseRepository;
	private final CourseStoreRepository courseStoreRepository;
	private final CourseImageFileRepository courseImageFileRepository;
	private final StoreRepository storeRepository;
	private final S3Service s3Service;
	private final ImageProcessingCallbackService imageProcessingCallbackService;

	// ───────────────────────── 코스 CRUD ─────────────────────────

	public Long createCuratedCourse(AdminCourseCreateRequest request) {
		Course course = Course.createCurated(request.getTitle(), request.getBadge());
		return courseRepository.save(course).getId();
	}

	public void updateCuratedCourse(Long courseId, AdminCourseUpdateRequest request) {
		Course course = findCourseOrThrow(courseId);
		if (request.getTitle() != null) {
			course.updateTitle(request.getTitle());
		}
		if (request.getBadge() != null) {
			course.updateBadge(request.getBadge());
		}
	}

	public void deleteCuratedCourse(Long courseId) {
		Course course = findCourseOrThrow(courseId);
		course.softDelete();
	}

	// ───────────────────────── 이미지 관리 ─────────────────────────

	public PresignedUrlResponse getPresignedUrl(Long courseId, String mimeType) {
		findCourseOrThrow(courseId);
		return s3Service.generatePresignedUrl(S3PathGenerator.getCoursePath(courseId), mimeType);
	}

	public void registerImage(Long courseId, CourseImageRegisterRequest request) {
		Course course = findCourseOrThrow(courseId);
		courseImageFileRepository.findByCourseId(courseId).ifPresent(existing -> {
			s3Service.deleteObject(existing.getAwsKey());
			courseImageFileRepository.delete(existing);
		});
		CourseImageFile imageFile = CourseImageFile.builder()
			.course(course)
			.name(request.getName())
			.mimeType(MimeType.fromString(request.getMimeType()))
			.awsKey(request.getAwsKey())
			.displayOrder(1)
			.build();
		imageProcessingCallbackService.applyPendingCallback(courseImageFileRepository.save(imageFile));
	}

	public void deleteImage(Long courseId) {
		findCourseOrThrow(courseId);
		CourseImageFile imageFile = courseImageFileRepository.findByCourseId(courseId)
			.orElseThrow(() -> new CourseException(CourseErrorCode.COURSE_IMAGE_NOT_FOUND));
		s3Service.deleteObject(imageFile.getAwsKey());
		courseImageFileRepository.delete(imageFile);
	}

	// ───────────────────────── 가게 관리 ─────────────────────────

	public CourseStoreResponse addStore(Long courseId, CourseStoreAddRequest request) {
		Course course = findCourseOrThrow(courseId);
		if (courseStoreRepository.existsByCourseIdAndStoreIdAndDeletedAtIsNull(courseId, request.getStoreId())) {
			throw new CourseException(CourseErrorCode.COURSE_STORE_ALREADY_EXISTS);
		}
		Store store = storeRepository.findById(request.getStoreId())
			.orElseThrow(() -> new StoreException(StoreErrorCode.STORE_NOT_FOUND));
		int nextOrder = courseStoreRepository.findMaxVisitOrderByCourseId(courseId) + 1;
		CourseStore courseStore = CourseStore.createWithDetails(
			course, store, nextOrder,
			request.getMemo(), request.getVisitTime());
		courseStoreRepository.save(courseStore);
		return CourseStoreResponse.from(courseStore);
	}

	public void updateStore(Long courseId, Long storeId, CourseStoreUpdateRequest request) {
		findCourseOrThrow(courseId);
		CourseStore courseStore = courseStoreRepository.findByCourseIdAndStoreIdAndDeletedAtIsNull(courseId, storeId)
			.orElseThrow(() -> new CourseException(CourseErrorCode.COURSE_STORE_NOT_FOUND));
		courseStore.updateMemo(request.getMemo());
		courseStore.updateVisitTime(request.getVisitTime());
	}

	public void removeStore(Long courseId, Long storeId) {
		findCourseOrThrow(courseId);
		CourseStore courseStore = courseStoreRepository.findByCourseIdAndStoreIdAndDeletedAtIsNull(courseId, storeId)
			.orElseThrow(() -> new CourseException(CourseErrorCode.COURSE_STORE_NOT_FOUND));
		courseStore.softDelete();
	}

	public void reorderStores(Long courseId, CourseStoreReorderRequest request) {
		findCourseOrThrow(courseId);
		List<CourseStoreOrderItem> orderItems = request.getCourseStoreOrders();
		List<Long> courseStoreIds = orderItems.stream()
			.map(CourseStoreOrderItem::getCourseStoreId)
			.toList();
		validateCompleteOrder(courseId, courseStoreIds);
		Map<Long, CourseStore> courseStoreMap = courseStoreRepository.findAllById(courseStoreIds).stream()
			.collect(Collectors.toMap(CourseStore::getId, cs -> cs));
		List<CourseStore> orderedCourseStores = orderItems.stream()
			.map(item -> {
				CourseStore courseStore = courseStoreMap.get(item.getCourseStoreId());
				if (courseStore == null || courseStore.isDeleted()
					|| !courseStore.getCourse().getId().equals(courseId)) {
					throw new CourseException(CourseErrorCode.COURSE_STORE_NOT_FOUND);
				}
				return courseStore;
			})
			.toList();
		// (course_id, visit_order) 유니크 제약과의 스왑 충돌을 피하기 위해 임시 음수 순서로 옮긴 뒤 최종 순서 부여
		IntStream.range(0, orderedCourseStores.size())
			.forEach(i -> orderedCourseStores.get(i).updateOrder(-(i + 1)));
		courseStoreRepository.flush();
		IntStream.range(0, orderedCourseStores.size())
			.forEach(i -> orderedCourseStores.get(i).updateOrder(i + 1));
	}

	private void validateCompleteOrder(Long courseId, List<Long> courseStoreIds) {
		long activeStoreCount = courseStoreRepository.countByCourseIdAndDeletedAtIsNull(courseId);
		if (courseStoreIds.contains(null)
			|| courseStoreIds.size() != activeStoreCount
			|| new HashSet<>(courseStoreIds).size() != courseStoreIds.size()) {
			throw new CourseException(CourseErrorCode.COURSE_STORE_ORDER_INVALID);
		}
	}

	// ───────────────────────── 핀 관리 ─────────────────────────

	public void pinCourse(Long courseId, AdminCoursePinRequest request) {
		Course course = findCourseOrThrow(courseId);
		if (course.isPinned()) {
			throw new CourseException(CourseErrorCode.COURSE_ALREADY_PINNED);
		}
		if (courseRepository.countByPinOrderIsNotNullAndDeletedAtIsNull() >= 3) {
			throw new CourseException(CourseErrorCode.COURSE_PIN_LIMIT_EXCEEDED);
		}
		courseRepository.findByPinOrderAndDeletedAtIsNull(request.getPinOrder()).ifPresent(existing -> {
			throw new CourseException(CourseErrorCode.COURSE_PIN_ORDER_CONFLICT);
		});
		course.pin(request.getPinOrder());
	}

	public void unpinCourse(Long courseId) {
		Course course = findCourseOrThrow(courseId);
		if (!course.isPinned()) {
			throw new CourseException(CourseErrorCode.COURSE_NOT_PINNED);
		}
		course.unpin();
	}

	// ───────────────────────── helpers ─────────────────────────

	private Course findCourseOrThrow(Long courseId) {
		return courseRepository.findByIdAndDeletedAtIsNull(courseId)
			.orElseThrow(() -> new CourseException(CourseErrorCode.COURSE_NOT_FOUND));
	}
}
