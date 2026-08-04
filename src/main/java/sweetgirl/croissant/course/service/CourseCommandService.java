package sweetgirl.croissant.course.service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.common.enums.MimeType;
import sweetgirl.croissant.course.code.CourseErrorCode;
import sweetgirl.croissant.course.dto.request.CourseCreateRequest;
import sweetgirl.croissant.course.dto.request.CourseImageRegisterRequest;
import sweetgirl.croissant.course.dto.request.CourseStoreAddRequest;
import sweetgirl.croissant.course.dto.request.CourseStoreReorderRequest;
import sweetgirl.croissant.course.dto.request.CourseStoreReorderRequest.CourseStoreOrderItem;
import sweetgirl.croissant.course.dto.request.CourseStoreUpdateRequest;
import sweetgirl.croissant.course.dto.request.CourseUpdateRequest;
import sweetgirl.croissant.course.dto.response.CourseStoreResponse;
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
import sweetgirl.croissant.external.aws.S3Service;
import sweetgirl.croissant.external.aws.dto.PresignedUrlResponse;
import sweetgirl.croissant.external.aws.service.ImageProcessingCallbackService;
import sweetgirl.croissant.external.aws.util.S3PathGenerator;
import sweetgirl.croissant.member.code.MemberErrorCode;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.exception.MemberException;
import sweetgirl.croissant.member.repository.MemberRepository;
import sweetgirl.croissant.store.code.StoreErrorCode;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.exception.StoreException;
import sweetgirl.croissant.store.repository.StoreRepository;

@Service
@RequiredArgsConstructor
@Transactional
public class CourseCommandService {

	private final CourseRepository courseRepository;
	private final CourseStoreRepository courseStoreRepository;
	private final CourseImageFileRepository courseImageFileRepository;
	private final SavedCourseRepository savedCourseRepository;
	private final MemberRepository memberRepository;
	private final StoreRepository storeRepository;
	private final S3Service s3Service;
	private final ImageProcessingCallbackService imageProcessingCallbackService;
	private final CourseProgressService courseProgressService;

	public Long createCourse(Long memberId, CourseCreateRequest request) {
		Member member = memberRepository.findByIdAndDeletedAtIsNull(memberId)
			.orElseThrow(() -> new MemberException(MemberErrorCode.MEMBER_NOT_FOUND));
		Course course = Course.createCustom(request.getTitle(), member, request.isPublic());
		return courseRepository.save(course).getId();
	}

	public void updateCourse(Long memberId, Long courseId, CourseUpdateRequest request) {
		Course course = courseRepository.findByIdAndDeletedAtIsNull(courseId)
			.orElseThrow(() -> new CourseException(CourseErrorCode.COURSE_NOT_FOUND));

		if (course.getType() == CourseType.CURATED) {
			throw new CourseException(CourseErrorCode.COURSE_FORBIDDEN);
		}

		validateOwner(course, memberId);

		// FORKED 코스는 원본과 동일한 채로 '공개'만 막는다. 비공개 복제는 동일해도 허용.
		if (course.getType() == CourseType.FORKED) {
			boolean willBePublic = request.getIsPublic() != null ? request.getIsPublic() : course.isPublic();
			if (willBePublic) {
				String effectiveTitle = request.getTitle() != null ? request.getTitle() : course.getTitle();
				if (isSameAsSource(course, effectiveTitle)) {
					throw new CourseException(CourseErrorCode.COURSE_FORK_NOT_MODIFIED);
				}
			}
		}

		if (request.getTitle() != null) {
			course.updateTitle(request.getTitle());
		}
		if (request.getIsPublic() != null) {
			course.updatePublic(request.getIsPublic());
		}
	}

	public void deleteCourse(Long memberId, Long courseId) {
		Course course = courseRepository.findByIdAndDeletedAtIsNull(courseId)
			.orElseThrow(() -> new CourseException(CourseErrorCode.COURSE_NOT_FOUND));
		validateOwner(course, memberId);
		course.softDelete();
	}

	public CourseStoreResponse addStore(Long memberId, Long courseId, CourseStoreAddRequest request) {
		Course course = courseRepository.findByIdAndDeletedAtIsNull(courseId)
			.orElseThrow(() -> new CourseException(CourseErrorCode.COURSE_NOT_FOUND));
		validateOwner(course, memberId);

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
		courseProgressService.refreshForCourse(courseId);

		return CourseStoreResponse.from(courseStore);
	}

	public void updateStore(Long memberId, Long courseId, Long storeId, CourseStoreUpdateRequest request) {
		Course course = courseRepository.findByIdAndDeletedAtIsNull(courseId)
			.orElseThrow(() -> new CourseException(CourseErrorCode.COURSE_NOT_FOUND));
		validateOwner(course, memberId);

		CourseStore courseStore = courseStoreRepository.findByCourseIdAndStoreIdAndDeletedAtIsNull(courseId, storeId)
			.orElseThrow(() -> new CourseException(CourseErrorCode.COURSE_STORE_NOT_FOUND));

		courseStore.updateMemo(request.getMemo());
		courseStore.updateVisitTime(request.getVisitTime());
	}

	public void removeStore(Long memberId, Long courseId, Long storeId) {
		Course course = courseRepository.findByIdAndDeletedAtIsNull(courseId)
			.orElseThrow(() -> new CourseException(CourseErrorCode.COURSE_NOT_FOUND));
		validateOwner(course, memberId);

		CourseStore courseStore = courseStoreRepository.findByCourseIdAndStoreIdAndDeletedAtIsNull(courseId, storeId)
			.orElseThrow(() -> new CourseException(CourseErrorCode.COURSE_STORE_NOT_FOUND));

		courseStore.softDelete();
		courseProgressService.refreshForCourse(courseId);
	}

	public void reorderStores(Long memberId, Long courseId, CourseStoreReorderRequest request) {
		Course course = courseRepository.findByIdAndDeletedAtIsNull(courseId)
			.orElseThrow(() -> new CourseException(CourseErrorCode.COURSE_NOT_FOUND));
		validateOwner(course, memberId);

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

	public PresignedUrlResponse getPresignedUrl(Long memberId, Long courseId, String mimeType) {
		Course course = courseRepository.findByIdAndDeletedAtIsNull(courseId)
			.orElseThrow(() -> new CourseException(CourseErrorCode.COURSE_NOT_FOUND));
		validateOwner(course, memberId);
		return s3Service.generatePresignedUrl(S3PathGenerator.getCoursePath(courseId), mimeType);
	}

	public void registerImage(Long memberId, Long courseId, CourseImageRegisterRequest request) {
		Course course = courseRepository.findByIdAndDeletedAtIsNull(courseId)
			.orElseThrow(() -> new CourseException(CourseErrorCode.COURSE_NOT_FOUND));
		validateOwner(course, memberId);

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

	public void deleteImage(Long memberId, Long courseId) {
		Course course = courseRepository.findByIdAndDeletedAtIsNull(courseId)
			.orElseThrow(() -> new CourseException(CourseErrorCode.COURSE_NOT_FOUND));
		validateOwner(course, memberId);

		CourseImageFile imageFile = courseImageFileRepository.findByCourseId(courseId)
			.orElseThrow(() -> new CourseException(CourseErrorCode.COURSE_IMAGE_NOT_FOUND));

		s3Service.deleteObject(imageFile.getAwsKey());
		courseImageFileRepository.delete(imageFile);
	}

	public Long forkCourse(Long memberId, Long courseId) {
		Course sourceCourse = courseRepository.findByIdAndDeletedAtIsNull(courseId)
			.orElseThrow(() -> new CourseException(CourseErrorCode.COURSE_NOT_FOUND));

		// 공개 코스는 누구나, 비공개 코스는 본인 것만 복제(fork) 가능.
		boolean isOwner = sourceCourse.getMember() != null
			&& sourceCourse.getMember().getId().equals(memberId);
		if (!sourceCourse.isPublic() && !isOwner) {
			throw new CourseException(CourseErrorCode.COURSE_FORK_NOT_PUBLIC);
		}

		Member member = memberRepository.findByIdAndDeletedAtIsNull(memberId)
			.orElseThrow(() -> new MemberException(MemberErrorCode.MEMBER_NOT_FOUND));

		String forkedTitle = "copy of " + sourceCourse.getTitle();
		Course savedForkedCourse = courseRepository.save(
			Course.createForked(forkedTitle, member, sourceCourse.getId()));

		List<CourseStore> sourceStores = courseStoreRepository
			.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(courseId);
		List<CourseStore> copiedStores = sourceStores.stream()
			.map(source -> CourseStore.createWithDetails(
				savedForkedCourse,
				source.getStore(),
				source.getVisitOrder(),
				source.getMemo(),
				source.getVisitTime()))
			.toList();
		courseStoreRepository.saveAll(copiedStores);

		return savedForkedCourse.getId();
	}

	private void validateOwner(Course course, Long memberId) {
		if (course.getMember() == null || !course.getMember().getId().equals(memberId)) {
			throw new CourseException(CourseErrorCode.COURSE_FORBIDDEN);
		}
	}

	private boolean isSameAsSource(Course course, String newTitle) {
		if (course.getSourceCourseId() == null) {
			return false;
		}
		Course sourceCourse = courseRepository.findByIdAndDeletedAtIsNull(course.getSourceCourseId())
			.orElse(null);
		if (sourceCourse == null) {
			return false;
		}
		if (!newTitle.equals(sourceCourse.getTitle())) {
			return false;
		}
		List<CourseStore> currentStores = courseStoreRepository
			.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(course.getId());
		List<CourseStore> sourceStores = courseStoreRepository
			.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(sourceCourse.getId());
		if (currentStores.size() != sourceStores.size()) {
			return false;
		}
		for (int i = 0; i < currentStores.size(); i++) {
			CourseStore current = currentStores.get(i);
			CourseStore source = sourceStores.get(i);
			if (!current.getStore().getId().equals(source.getStore().getId())) {
				return false;
			}
			if (!current.getVisitOrder().equals(source.getVisitOrder())) {
				return false;
			}
			if (!Objects.equals(current.getMemo(), source.getMemo())) {
				return false;
			}
			if (!Objects.equals(current.getVisitTime(), source.getVisitTime())) {
				return false;
			}
		}
		return true;
	}
}
