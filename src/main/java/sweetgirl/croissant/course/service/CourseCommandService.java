package sweetgirl.croissant.course.service;

import java.time.LocalDate;
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
import sweetgirl.croissant.course.repository.CourseMemberRepository;
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
	private final CourseMemberRepository courseMemberRepository;
	private final MemberRepository memberRepository;
	private final StoreRepository storeRepository;
	private final S3Service s3Service;
	private final ImageProcessingCallbackService imageProcessingCallbackService;
	private final CourseProgressService courseProgressService;

	public Long createCourse(Long memberId, CourseCreateRequest request) {
		Member member = memberRepository.findByIdAndDeletedAtIsNull(memberId)
			.orElseThrow(() -> new MemberException(MemberErrorCode.MEMBER_NOT_FOUND));
		validateDates(request.getStartDate(), request.getEndDate());
		Course course = Course.createCustom(
			request.getTitle(),
			member,
			Boolean.TRUE.equals(request.getIsPublic()),
			request.getRegion(),
			request.getStartDate(),
			request.getEndDate());
		return courseRepository.save(course).getId();
	}

	/** 시작·종료일은 한 쌍이다 — 하나만 오거나 순서가 뒤집히면 계획으로 쓸 수 없다. */
	private void validateDates(LocalDate startDate, LocalDate endDate) {
		if ((startDate == null) != (endDate == null)) {
			throw new CourseException(CourseErrorCode.COURSE_DATES_INVALID);
		}
		if (startDate != null && startDate.isAfter(endDate)) {
			throw new CourseException(CourseErrorCode.COURSE_DATES_INVALID);
		}
	}

	public void updateCourse(Long memberId, Long courseId, CourseUpdateRequest request) {
		Course course = courseRepository.findByIdAndDeletedAtIsNull(courseId)
			.orElseThrow(() -> new CourseException(CourseErrorCode.COURSE_NOT_FOUND));

		if (course.getType() == CourseType.CURATED) {
			throw new CourseException(CourseErrorCode.COURSE_FORBIDDEN);
		}

		validateCanEdit(course, memberId);
		// 공개/비공개 전환은 방장 전용이다. 협업 멤버는 제목·지역·날짜만 고친다.
		if (request.getIsPublic() != null) {
			validateOwner(course, memberId);
		}
		// 같이 편집 중인 코스는 공개할 수 없다 — 방장 말고 활성 멤버가 1명 이상일 때. 방장 행만 있으면 아직 협업이 아니다.
		if (Boolean.TRUE.equals(request.getIsPublic()) && hasCollaborators(course, memberId)) {
			throw new CourseException(CourseErrorCode.COURSE_COLLAB_CANNOT_BE_PUBLIC);
		}

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
		if (request.getRegion() != null) {
			course.updateRegion(request.getRegion());
		}
		if (request.getStartDate() != null || request.getEndDate() != null) {
			validateDates(request.getStartDate(), request.getEndDate());
			course.updateDates(request.getStartDate(), request.getEndDate());
		}
		if (request.getDayCount() != null) {
			updateDayCount(courseId, request.getDayCount());
		}
		courseRepository.bumpVersion(courseId);
	}

	private boolean hasCollaborators(Course course, Long ownerId) {
		return courseMemberRepository.findAllByIdCourseIdAndDeletedAtIsNullOrderByJoinedAtAsc(course.getId()).stream()
			.anyMatch(row -> !row.getId().getMemberId().equals(ownerId));
	}

	/**
	 * 일차 수는 늘어나기만 한다. 일행이 동시에 뒤 일차에 가게를 담을 수 있으니
	 * 코스 행에 락을 잡고 현재 값·가게가 있는 마지막 일차와 비교한다.
	 */
	private void updateDayCount(Long courseId, int dayCount) {
		Course lockedCourse = courseRepository.findWithLockByIdAndDeletedAtIsNull(courseId)
			.orElseThrow(() -> new CourseException(CourseErrorCode.COURSE_NOT_FOUND));
		int maxDay = courseStoreRepository.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(courseId).stream()
			.mapToInt(CourseStore::getDay)
			.max()
			.orElse(0);
		if (dayCount < lockedCourse.getDayCount() || dayCount < maxDay) {
			throw new CourseException(CourseErrorCode.COURSE_DAY_COUNT_INVALID);
		}
		lockedCourse.updateDayCount(dayCount);
	}

	public void deleteCourse(Long memberId, Long courseId) {
		Course course = courseRepository.findByIdAndDeletedAtIsNull(courseId)
			.orElseThrow(() -> new CourseException(CourseErrorCode.COURSE_NOT_FOUND));
		validateOwner(course, memberId);
		course.softDelete();
	}

	public CourseStoreResponse addStore(Long memberId, Long courseId, CourseStoreAddRequest request) {
		Course course = courseRepository.findWithLockByIdAndDeletedAtIsNull(courseId)
			.orElseThrow(() -> new CourseException(CourseErrorCode.COURSE_NOT_FOUND));
		validateCanEdit(course, memberId);

		if (courseStoreRepository.existsByCourseIdAndStoreIdAndDeletedAtIsNull(courseId, request.getStoreId())) {
			throw new CourseException(CourseErrorCode.COURSE_STORE_ALREADY_EXISTS);
		}

		Store store = storeRepository.findById(request.getStoreId())
			.orElseThrow(() -> new StoreException(StoreErrorCode.STORE_NOT_FOUND));

		List<CourseStore> activeStores = courseStoreRepository
			.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(courseId);
		int maxDay = activeStores.stream().mapToInt(CourseStore::getDay).max().orElse(0);
		int day = request.getDay() != null ? request.getDay() : Math.max(maxDay, 1);
		if (day < 1 || day > maxDay + 1) {
			throw new CourseException(CourseErrorCode.COURSE_STORE_DAY_INVALID);
		}
		// 뒤 일차에 담기면 일차 수도 따라 올린다 — 일행이 같은 일차 수를 본다
		course.ensureDayCountAtLeast(day);

		// 삽입 위치: 해당 일차의 마지막 가게 뒤
		int insertIndex = 0;
		for (int i = 0; i < activeStores.size(); i++) {
			if (activeStores.get(i).getDay() <= day) {
				insertIndex = i + 1;
			}
		}

		CourseStore courseStore;
		if (insertIndex == activeStores.size()) {
			int nextOrder = courseStoreRepository.findMaxVisitOrderByCourseId(courseId) + 1;
			courseStore = CourseStore.createWithDetails(
				course, store, nextOrder, day,
				request.getMemo(), request.getVisitTime());
			courseStoreRepository.save(courseStore);
		} else {
			// 중간 삽입: 유니크 제약 충돌을 피하기 위해 기존 가게를 임시 음수 순서로 옮긴 뒤 끼워 넣고 재부여
			IntStream.range(0, activeStores.size())
				.forEach(i -> activeStores.get(i).updateOrder(-(i + 1)));
			courseStoreRepository.flush();
			courseStore = CourseStore.createWithDetails(
				course, store, insertIndex + 1, day,
				request.getMemo(), request.getVisitTime());
			courseStoreRepository.save(courseStore);
			int finalInsertIndex = insertIndex;
			IntStream.range(0, activeStores.size())
				.forEach(i -> activeStores.get(i).updateOrder(i < finalInsertIndex ? i + 1 : i + 2));
		}
		courseProgressService.refreshForCourse(courseId);
		courseRepository.bumpVersion(courseId);

		return CourseStoreResponse.from(courseStore);
	}

	public void updateStore(Long memberId, Long courseId, Long storeId, CourseStoreUpdateRequest request) {
		Course course = courseRepository.findByIdAndDeletedAtIsNull(courseId)
			.orElseThrow(() -> new CourseException(CourseErrorCode.COURSE_NOT_FOUND));
		validateCanEdit(course, memberId);

		CourseStore courseStore = courseStoreRepository.findByCourseIdAndStoreIdAndDeletedAtIsNull(courseId, storeId)
			.orElseThrow(() -> new CourseException(CourseErrorCode.COURSE_STORE_NOT_FOUND));

		courseStore.updateMemo(request.getMemo());
		courseStore.updateVisitTime(request.getVisitTime());
		courseRepository.bumpVersion(courseId);
	}

	public void removeStore(Long memberId, Long courseId, Long storeId) {
		Course course = courseRepository.findWithLockByIdAndDeletedAtIsNull(courseId)
			.orElseThrow(() -> new CourseException(CourseErrorCode.COURSE_NOT_FOUND));
		validateCanEdit(course, memberId);

		CourseStore courseStore = courseStoreRepository.findByCourseIdAndStoreIdAndDeletedAtIsNull(courseId, storeId)
			.orElseThrow(() -> new CourseException(CourseErrorCode.COURSE_STORE_NOT_FOUND));

		courseStore.softDelete();
		courseProgressService.refreshForCourse(courseId);
		courseRepository.bumpVersion(courseId);
	}

	public void reorderStores(Long memberId, Long courseId, CourseStoreReorderRequest request) {
		Course course = courseRepository.findWithLockByIdAndDeletedAtIsNull(courseId)
			.orElseThrow(() -> new CourseException(CourseErrorCode.COURSE_NOT_FOUND));
		validateCanEdit(course, memberId);

		List<CourseStoreOrderItem> orderItems = request.getCourseStoreOrders();
		List<Long> courseStoreIds = orderItems.stream()
			.map(CourseStoreOrderItem::getCourseStoreId)
			.toList();
		validateCompleteOrder(courseId, courseStoreIds);
		// 순서 CAS — 두 사람이 같은 집합을 다른 순서로 동시에 저장하면 집합 검증은 둘 다 통과하고 후저장이 이긴다.
		// version 이 아니라 순서 자체를 비교한다: version 은 메모·제목 저장에도 오르므로
		// 친구가 메모만 써도 내 드래그가 거부되는 가짜 충돌이 난다. 구버전 앱은 expectedOrder 를 안 보내므로 건너뛴다.
		if (request.getExpectedOrder() != null) {
			validateExpectedOrder(courseId, request.getExpectedOrder());
		}

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

		boolean hasDayInfo = orderItems.stream().anyMatch(item -> item.getDay() != null);
		if (hasDayInfo) {
			validateDaySequence(orderItems);
			orderItems.stream().mapToInt(CourseStoreOrderItem::getDay).max().ifPresent(course::ensureDayCountAtLeast);
		}

		// (course_id, visit_order) 유니크 제약과의 스왑 충돌을 피하기 위해 임시 음수 순서로 옮긴 뒤 최종 순서 부여
		IntStream.range(0, orderedCourseStores.size())
			.forEach(i -> orderedCourseStores.get(i).updateOrder(-(i + 1)));
		courseStoreRepository.flush();
		IntStream.range(0, orderedCourseStores.size())
			.forEach(i -> {
				orderedCourseStores.get(i).updateOrder(i + 1);
				if (hasDayInfo) {
					orderedCourseStores.get(i).updateDay(orderItems.get(i).getDay());
				}
			});
		courseRepository.bumpVersion(courseId);
	}

	private void validateCompleteOrder(Long courseId, List<Long> courseStoreIds) {
		long activeStoreCount = courseStoreRepository.countByCourseIdAndDeletedAtIsNull(courseId);
		if (courseStoreIds.contains(null)
			|| courseStoreIds.size() != activeStoreCount
			|| new HashSet<>(courseStoreIds).size() != courseStoreIds.size()) {
			throw new CourseException(CourseErrorCode.COURSE_STORE_ORDER_INVALID);
		}
	}

	/** 락 안에서 현재 DB 의 활성 정류장 순서와 클라이언트가 본 순서를 리스트로 비교한다. 다르면 일행이 먼저 옮긴 것이다. */
	private void validateExpectedOrder(Long courseId, List<Long> expectedOrder) {
		List<Long> currentOrder = courseStoreRepository
			.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(courseId).stream()
			.map(CourseStore::getId)
			.toList();
		if (!currentOrder.equals(expectedOrder)) {
			throw new CourseException(CourseErrorCode.COURSE_STALE);
		}
	}

	// 일차는 1부터 시작해 빠짐없이 이어져야 한다 (1,1,2,2,3 ✓ / 1,3 ✗ / 2,1 ✗)
	private void validateDaySequence(List<CourseStoreOrderItem> orderItems) {
		int prev = 0;
		for (CourseStoreOrderItem item : orderItems) {
			Integer day = item.getDay();
			if (day == null || day < 1 || day < prev || day > prev + 1) {
				throw new CourseException(CourseErrorCode.COURSE_STORE_DAY_INVALID);
			}
			prev = day;
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
		courseRepository.bumpVersion(courseId);
	}

	public void deleteImage(Long memberId, Long courseId) {
		Course course = courseRepository.findByIdAndDeletedAtIsNull(courseId)
			.orElseThrow(() -> new CourseException(CourseErrorCode.COURSE_NOT_FOUND));
		validateOwner(course, memberId);

		CourseImageFile imageFile = courseImageFileRepository.findByCourseId(courseId)
			.orElseThrow(() -> new CourseException(CourseErrorCode.COURSE_IMAGE_NOT_FOUND));

		s3Service.deleteObject(imageFile.getAwsKey());
		courseImageFileRepository.delete(imageFile);
		courseRepository.bumpVersion(courseId);
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
		// 지역은 코스의 성격이라 따라오고, 날짜는 복제한 사람의 계획이라 비워 둔다.
		savedForkedCourse.updateRegion(sourceCourse.getRegion());

		List<CourseStore> sourceStores = courseStoreRepository
			.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(courseId);
		List<CourseStore> copiedStores = sourceStores.stream()
			.map(source -> CourseStore.createWithDetails(
				savedForkedCourse,
				source.getStore(),
				source.getVisitOrder(),
				source.getDay(),
				source.getMemo(),
				source.getVisitTime()))
			.toList();
		courseStoreRepository.saveAll(copiedStores);

		return savedForkedCourse.getId();
	}

	/** 방장 전용 — 코스 삭제 · 공개/비공개 전환 · 커버 이미지. */
	private void validateOwner(Course course, Long memberId) {
		if (!isOwner(course, memberId)) {
			throw new CourseException(CourseErrorCode.COURSE_FORBIDDEN);
		}
	}

	/**
	 * 방장 또는 활성 멤버 — 가게 추가·수정·삭제·순서 변경, 제목·지역·날짜 수정.
	 *
	 * ⚠️ 이 CURATED 검사를 지우지 말 것.
	 * 기존 validateOwner 는 course.getMember() == null 조건으로 큐레이션을 "우연히" 막고 있었다.
	 * 소유자 조건을 멤버까지 넓히는 순간 그 우연한 방어가 사라진다 —
	 * course_members 에 행 하나만 심으면 공식 추천 코스를 아무나 편집하게 된다.
	 */
	private void validateCanEdit(Course course, Long memberId) {
		if (course.getType() == CourseType.CURATED) {
			throw new CourseException(CourseErrorCode.COURSE_FORBIDDEN);
		}
		if (isOwner(course, memberId)) {
			return;
		}
		if (!courseMemberRepository.existsByIdCourseIdAndIdMemberIdAndDeletedAtIsNull(course.getId(), memberId)) {
			throw new CourseException(CourseErrorCode.COURSE_FORBIDDEN);
		}
	}

	private boolean isOwner(Course course, Long memberId) {
		return course.getMember() != null && course.getMember().getId().equals(memberId);
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
			if (!Objects.equals(current.getDay(), source.getDay())) {
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
