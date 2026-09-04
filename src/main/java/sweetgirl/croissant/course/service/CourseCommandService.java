package sweetgirl.croissant.course.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
import sweetgirl.croissant.course.dto.response.CourseStoreMemoResponse;
import sweetgirl.croissant.course.dto.response.CourseStoreResponse;
import sweetgirl.croissant.course.dto.response.CourseUpdateResponse;
import sweetgirl.croissant.course.entity.Course;
import sweetgirl.croissant.course.entity.CourseImageFile;
import sweetgirl.croissant.course.entity.CourseMember;
import sweetgirl.croissant.course.entity.CourseStore;
import sweetgirl.croissant.course.entity.CourseStoreMemo;
import sweetgirl.croissant.course.entity.SavedCourse;
import sweetgirl.croissant.course.enums.CourseType;
import sweetgirl.croissant.course.enums.CourseVisibility;
import sweetgirl.croissant.course.exception.CourseException;
import sweetgirl.croissant.course.repository.CourseImageFileRepository;
import sweetgirl.croissant.course.repository.CourseMemberRepository;
import sweetgirl.croissant.course.repository.CourseRepository;
import sweetgirl.croissant.course.repository.CourseStoreMemoRepository;
import sweetgirl.croissant.course.repository.CourseStoreRepository;
import sweetgirl.croissant.course.repository.SavedCourseRepository;
import sweetgirl.croissant.course.util.CollabTokenGenerator;
import sweetgirl.croissant.external.aws.S3Service;
import sweetgirl.croissant.external.aws.dto.PresignedUrlResponse;
import sweetgirl.croissant.external.aws.service.ImageProcessingCallbackService;
import sweetgirl.croissant.external.aws.util.S3PathGenerator;
import sweetgirl.croissant.member.code.MemberErrorCode;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.exception.MemberException;
import sweetgirl.croissant.member.repository.MemberRepository;
import sweetgirl.croissant.member.service.MemberRestrictionReader;
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
	private final CourseStoreMemoRepository courseStoreMemoRepository;
	private final MemberRepository memberRepository;
	private final StoreRepository storeRepository;
	private final S3Service s3Service;
	private final ImageProcessingCallbackService imageProcessingCallbackService;
	private final CourseProgressService courseProgressService;
	private final MemberRestrictionReader memberRestrictionReader;
	private final CourseAccessPolicy courseAccessPolicy;

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

	public CourseUpdateResponse updateCourse(Long memberId, Long courseId, CourseUpdateRequest request) {
		// 락으로 시작한다 — 공개 여부·일차 수 검사는 읽고-판단하고-쓰기라 락 없이는 수락·가게 추가와 경합한다.
		// 락 없이 읽은 엔티티를 나중에 락 조회로 다시 만나도 영속성 컨텍스트가 낡은 상태를 돌려주므로, 처음부터 락이어야 한다.
		Course course = courseRepository.findWithLockByIdAndDeletedAtIsNull(courseId)
			.orElseThrow(() -> new CourseException(CourseErrorCode.COURSE_NOT_FOUND));

		if (course.getType() == CourseType.CURATED) {
			throw new CourseException(CourseErrorCode.COURSE_FORBIDDEN);
		}

		courseAccessPolicy.requireEditable(course, memberId);
		CourseVisibility requestedVisibility = requestedVisibility(request);
		validateVisibilityChange(course, memberId, requestedVisibility, request.getTitle());

		if (request.getTitle() != null) {
			course.updateTitle(request.getTitle());
		}
		if (requestedVisibility != null) {
			applyVisibility(course, requestedVisibility);
		}
		if (request.getRegion() != null) {
			course.updateRegion(request.getRegion());
		}
		if (request.getStartDate() != null || request.getEndDate() != null) {
			validateDates(request.getStartDate(), request.getEndDate());
			course.updateDates(request.getStartDate(), request.getEndDate());
		}
		if (request.getDayCount() != null) {
			updateDayCount(course, request.getDayCount());
		}
		courseRepository.bumpVersion(courseId);
		return new CourseUpdateResponse(course.getVisibility(), shareTokenFor(course, memberId));
	}

	/**
	 * 공개 범위 전환 규칙. 방장 전용이고, 같이 편집 중인 코스는 PUBLIC 이 될 수 없으며(방장 행만 있으면 아직 협업이 아니다 —
	 * 링크 공개·PRIVATE 은 된다), FORKED 코스는 원본과 동일한 채로 PUBLIC 만 막는다(비공개·링크 공개 복제는 동일해도 허용).
	 */
	private void validateVisibilityChange(Course course, Long memberId, CourseVisibility requested, String newTitle) {
		if (requested == null) {
			// 공개 범위를 안 바꾸는 수정도 FORKED 가 이미 PUBLIC 이면 동일 판정을 탄다 — 제목만 원본으로 되돌리는 경우
			if (course.getType() == CourseType.FORKED && course.isPublic()) {
				validateForkModified(course, newTitle);
			}
			return;
		}
		courseAccessPolicy.requireOwner(course, memberId);
		if (requested == CourseVisibility.PUBLIC && hasCollaborators(course, memberId)) {
			throw new CourseException(CourseErrorCode.COURSE_COLLAB_CANNOT_BE_PUBLIC);
		}
		if (course.getType() == CourseType.FORKED && requested == CourseVisibility.PUBLIC) {
			validateForkModified(course, newTitle);
		}
	}

	private void validateForkModified(Course course, String newTitle) {
		String effectiveTitle = newTitle != null ? newTitle : course.getTitle();
		if (isSameAsSource(course, effectiveTitle)) {
			throw new CourseException(CourseErrorCode.COURSE_FORK_NOT_MODIFIED);
		}
	}

	/** 공유 토큰은 링크 공개로 처음 바꿀 때 발급하고 만료 없이 보존한다 — 왕복해도 이미 나간 링크가 살아 있어야 한다. */
	private void applyVisibility(Course course, CourseVisibility visibility) {
		course.updateVisibility(visibility);
		if (visibility == CourseVisibility.UNLISTED && course.getShareToken() == null) {
			course.issueShareToken(CollabTokenGenerator.generate());
		}
	}

	/** visibility 가 오면 그것, 아니면 구버전 앱의 isPublic(true → PUBLIC, false → PRIVATE). 둘 다 없으면 null. */
	private CourseVisibility requestedVisibility(CourseUpdateRequest request) {
		if (request.getVisibility() != null) {
			return request.getVisibility();
		}
		if (request.getIsPublic() != null) {
			return CourseVisibility.fromPublic(request.getIsPublic());
		}
		return null;
	}

	/** 공유 토큰은 방장에게만, 링크 공개일 때만 — 멤버가 제목만 고쳐도 같은 응답을 받는다. */
	private String shareTokenFor(Course course, Long memberId) {
		return courseAccessPolicy.isOwner(course, memberId) && course.getVisibility() == CourseVisibility.UNLISTED
			? course.getShareToken()
			: null;
	}

	private boolean hasCollaborators(Course course, Long ownerId) {
		return courseMemberRepository.existsByIdCourseIdAndIdMemberIdNotAndDeletedAtIsNull(course.getId(), ownerId);
	}

	/**
	 * 일차 수는 늘어나기만 한다. 일행이 동시에 뒤 일차에 가게를 담을 수 있으니
	 * 락을 잡은 코스의 현재 값·가게가 있는 마지막 일차와 비교한다.
	 */
	private void updateDayCount(Course lockedCourse, int dayCount) {
		int maxDay = courseStoreRepository.findMaxDayByCourseId(lockedCourse.getId());
		if (dayCount < lockedCourse.getDayCount() || dayCount < maxDay) {
			throw new CourseException(CourseErrorCode.COURSE_DAY_COUNT_INVALID);
		}
		lockedCourse.updateDayCount(dayCount);
	}

	public void deleteCourse(Long memberId, Long courseId) {
		Course course = courseRepository.findByIdAndDeletedAtIsNull(courseId)
			.orElseThrow(() -> new CourseException(CourseErrorCode.COURSE_NOT_FOUND));
		courseAccessPolicy.requireOwner(course, memberId);
		course.softDelete();
	}

	public CourseStoreResponse addStore(Long memberId, Long courseId, CourseStoreAddRequest request) {
		Course course = courseRepository.findWithLockByIdAndDeletedAtIsNull(courseId)
			.orElseThrow(() -> new CourseException(CourseErrorCode.COURSE_NOT_FOUND));
		courseAccessPolicy.requireEditable(course, memberId);

		// 활성 정류장을 한 번 읽어 중복 가게·일차·삽입 위치·다음 순서를 전부 판정한다 — exists·MAX 쿼리를 따로 내지 않는다
		List<CourseStore> activeStores = courseStoreRepository
			.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(courseId);
		if (containsStore(activeStores, request.getStoreId())) {
			throw new CourseException(CourseErrorCode.COURSE_STORE_ALREADY_EXISTS);
		}

		Store store = storeRepository.findById(request.getStoreId())
			.orElseThrow(() -> new StoreException(StoreErrorCode.STORE_NOT_FOUND));

		int day = resolveDay(request.getDay(), activeStores);
		// 뒤 일차에 담기면 일차 수도 따라 올린다 — 일행이 같은 일차 수를 본다
		course.ensureDayCountAtLeast(day);
		CourseStore courseStore = insertStore(course, store, activeStores, day, request.getVisitTime());
		// 메모는 사람별이다 — course_stores.memo 가 아니라 내 메모 행으로 들어간다
		String myMemo = saveMyMemo(courseStore, memberId, request.getMemo());
		// 가게가 늘었다 — 분모가 커졌으니 완주 승격을 허용해도 안전하다
		courseProgressService.refreshForCourse(courseId, true);
		courseRepository.bumpVersion(courseId);

		List<CourseStoreMemoResponse> memos = myMemo == null
			? List.of()
			: List.of(new CourseStoreMemoResponse(
				memberId, displayNameOf(courseId, memberId), myMemo, LocalDateTime.now(), true));
		return CourseStoreResponse.of(courseStore, null, myMemo, myMemo, memos);
	}

	/** 요청 일차가 없으면 마지막 일차(최소 1). 1 ~ (마지막 일차 + 1) 만 허용 — 빈 일차를 건너뛰어 담을 수 없다. */
	private int resolveDay(Integer requestedDay, List<CourseStore> activeStores) {
		int maxDay = activeStores.stream().mapToInt(CourseStore::getDay).max().orElse(0);
		int day = requestedDay != null ? requestedDay : Math.max(maxDay, 1);
		if (day < 1 || day > maxDay + 1) {
			throw new CourseException(CourseErrorCode.COURSE_STORE_DAY_INVALID);
		}
		return day;
	}

	private boolean containsStore(List<CourseStore> activeStores, Long storeId) {
		return activeStores.stream().anyMatch(courseStore -> courseStore.getStore().getId().equals(storeId));
	}

	/**
	 * 해당 일차의 마지막 가게 뒤에 끼워 넣는다.
	 * 중간 삽입은 (course_id, visit_order) 유니크 충돌을 피하려고 기존 가게를 임시 음수 순서로 옮긴 뒤 끼우고 다시 매긴다.
	 */
	private CourseStore insertStore(Course course, Store store, List<CourseStore> activeStores, int day,
			LocalTime visitTime) {
		int insertIndex = insertIndexFor(activeStores, day);
		if (insertIndex == activeStores.size()) {
			// 순서 유니크는 활성 행 기준이라 활성 정류장의 최대 순서 다음이면 된다
			int nextOrder = activeStores.stream().mapToInt(CourseStore::getVisitOrder).max().orElse(0) + 1;
			CourseStore appended = CourseStore.createWithDetails(course, store, nextOrder, day, null, visitTime);
			courseStoreRepository.save(appended);
			return appended;
		}
		IntStream.range(0, activeStores.size())
			.forEach(i -> activeStores.get(i).updateOrder(-(i + 1)));
		courseStoreRepository.flush();
		CourseStore inserted = CourseStore.createWithDetails(course, store, insertIndex + 1, day, null, visitTime);
		courseStoreRepository.save(inserted);
		IntStream.range(0, activeStores.size())
			.forEach(i -> activeStores.get(i).updateOrder(i < insertIndex ? i + 1 : i + 2));
		return inserted;
	}

	/** 삽입 위치 = 해당 일차(또는 그 앞 일차)의 마지막 가게 뒤. */
	private int insertIndexFor(List<CourseStore> activeStores, int day) {
		int insertIndex = 0;
		for (int i = 0; i < activeStores.size(); i++) {
			if (activeStores.get(i).getDay() <= day) {
				insertIndex = i + 1;
			}
		}
		return insertIndex;
	}

	/** 방금 만든 정류장의 내 메모 — 기존 행이 있을 수 없으니 조회 없이 저장한다. 빈 값이면 아무것도 안 한다. */
	private String saveMyMemo(CourseStore courseStore, Long memberId, String content) {
		if (content == null || content.isBlank()) {
			return null;
		}
		memberRestrictionReader.validateCanWrite(memberId);
		Member author = memberRepository.getReferenceById(memberId);
		courseStoreMemoRepository.save(CourseStoreMemo.of(courseStore, author, content));
		return content;
	}

	/**
	 * 내 메모 upsert. 빈 값이면 내 메모를 지운다. 지웠던 행은 같은 PK 로 되살린다.
	 * 큐레이션 코스는 이 경로에 못 온다(validateCanEdit) — 그쪽 메모는 course_stores.memo 그대로다.
	 *
	 * @return 저장된 내용. 지웠거나 없으면 null
	 */
	private String upsertMyMemo(CourseStore courseStore, Long memberId, String content) {
		Optional<CourseStoreMemo> existing = courseStoreMemoRepository
			.findByIdCourseStoreIdAndIdMemberId(courseStore.getId(), memberId);
		if (content == null || content.isBlank()) {
			existing.filter(memo -> !memo.isDeleted()).ifPresent(CourseStoreMemo::softDelete);
			return null;
		}
		// 메모는 일행에게 보이는 글이다 — 쓰기 제한(밴) 회원은 초대 생성·수락처럼 막는다. 지우는 건 된다.
		memberRestrictionReader.validateCanWrite(memberId);
		if (existing.isPresent()) {
			CourseStoreMemo memo = existing.get();
			if (memo.isDeleted()) {
				memo.restore(content);
			} else {
				memo.updateContent(content);
			}
			return content;
		}
		Member author = memberRepository.getReferenceById(memberId);
		courseStoreMemoRepository.save(CourseStoreMemo.of(courseStore, author, content));
		return content;
	}

	/** 코스 안에서 쓰는 이름. 행이 없으면(협업 전 코스) 닉네임. */
	private String displayNameOf(Long courseId, Long memberId) {
		return courseMemberRepository.findByIdCourseIdAndIdMemberId(courseId, memberId)
			.filter(row -> !row.isDeleted())
			.map(CourseMember::getDisplayName)
			.orElseGet(() -> memberRepository.findByIdAndDeletedAtIsNull(memberId)
				.map(Member::getNickname)
				.orElse(null));
	}

	public void updateStore(Long memberId, Long courseId, Long storeId, CourseStoreUpdateRequest request) {
		// 락으로 시작한다 — 같은 사람의 이중 탭이 메모 행을 두 번 INSERT 하지 않게, 그리고 가게 삭제·순서 변경과
		// 락 순서(courses → course_stores)를 맞춰 데드락을 피한다.
		Course course = courseRepository.findWithLockByIdAndDeletedAtIsNull(courseId)
			.orElseThrow(() -> new CourseException(CourseErrorCode.COURSE_NOT_FOUND));
		courseAccessPolicy.requireEditable(course, memberId);

		CourseStore courseStore = courseStoreRepository.findByCourseIdAndStoreIdAndDeletedAtIsNull(courseId, storeId)
			.orElseThrow(() -> new CourseException(CourseErrorCode.COURSE_STORE_NOT_FOUND));

		// memo 는 내 메모만 바꾼다(사람별). visitTime 은 공통 한 개라 정류장에 그대로.
		upsertMyMemo(courseStore, memberId, request.getMemo());
		courseStore.updateVisitTime(request.getVisitTime());
		courseRepository.bumpVersion(courseId);
	}

	public void removeStore(Long memberId, Long courseId, Long storeId) {
		Course course = courseRepository.findWithLockByIdAndDeletedAtIsNull(courseId)
			.orElseThrow(() -> new CourseException(CourseErrorCode.COURSE_NOT_FOUND));
		courseAccessPolicy.requireEditable(course, memberId);

		CourseStore courseStore = courseStoreRepository.findByCourseIdAndStoreIdAndDeletedAtIsNull(courseId, storeId)
			.orElseThrow(() -> new CourseException(CourseErrorCode.COURSE_STORE_NOT_FOUND));

		courseStore.softDelete();
		// 메모는 정류장 행에 묶인다 — 가게를 빼면 전원 메모가 함께 닫히고, 다시 담아도 돌아오지 않는다
		courseStoreMemoRepository.softDeleteAllByCourseStoreId(courseStore.getId());
		// 가게가 줄었다 — 방문 수는 그대로인데 분모만 작아지므로 완주 승격을 막는다(안 간 코스가 완주 처리된다)
		courseProgressService.refreshForCourse(courseId, false);
		courseRepository.bumpVersion(courseId);
	}

	public void reorderStores(Long memberId, Long courseId, CourseStoreReorderRequest request) {
		Course course = courseRepository.findWithLockByIdAndDeletedAtIsNull(courseId)
			.orElseThrow(() -> new CourseException(CourseErrorCode.COURSE_NOT_FOUND));
		courseAccessPolicy.requireEditable(course, memberId);

		List<CourseStoreOrderItem> orderItems = request.getCourseStoreOrders();
		List<Long> courseStoreIds = orderItems.stream()
			.map(CourseStoreOrderItem::getCourseStoreId)
			.toList();
		// 락 안에서 활성 정류장을 한 번 읽어 집합 검증·순서 CAS·갱신할 행 조회에 다 쓴다 — count·findAllById 를 따로 내지 않는다
		List<CourseStore> activeStores = courseStoreRepository
			.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(courseId);
		validateCompleteOrder(activeStores.size(), courseStoreIds);
		// 순서 CAS — 두 사람이 같은 집합을 다른 순서로 동시에 저장하면 집합 검증은 둘 다 통과하고 후저장이 이긴다.
		// version 이 아니라 순서 자체를 비교한다: version 은 메모·제목 저장에도 오르므로
		// 친구가 메모만 써도 내 드래그가 거부되는 가짜 충돌이 난다. 구버전 앱은 expectedOrder 를 안 보내므로 건너뛴다.
		if (request.getExpectedOrder() != null) {
			validateExpectedOrder(activeStores, request.getExpectedOrder());
		}

		Map<Long, CourseStore> courseStoreById = activeStores.stream()
			.collect(Collectors.toMap(CourseStore::getId, courseStore -> courseStore));

		List<CourseStore> orderedCourseStores = orderItems.stream()
			.map(item -> {
				CourseStore courseStore = courseStoreById.get(item.getCourseStoreId());
				// 이 코스의 활성 정류장이 아니다 — 다른 코스의 행이거나 이미 뺀 행
				if (courseStore == null) {
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

	private void validateCompleteOrder(int activeStoreCount, List<Long> courseStoreIds) {
		if (courseStoreIds.contains(null)
			|| courseStoreIds.size() != activeStoreCount
			|| new HashSet<>(courseStoreIds).size() != courseStoreIds.size()) {
			throw new CourseException(CourseErrorCode.COURSE_STORE_ORDER_INVALID);
		}
	}

	/** 락 안에서 읽은 활성 정류장 순서와 클라이언트가 본 순서를 리스트로 비교한다. 다르면 일행이 먼저 옮긴 것이다. */
	private void validateExpectedOrder(List<CourseStore> activeStores, List<Long> expectedOrder) {
		List<Long> currentOrder = activeStores.stream()
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
		courseAccessPolicy.requireOwner(course, memberId);
		return s3Service.generatePresignedUrl(S3PathGenerator.getCoursePath(courseId), mimeType);
	}

	public void registerImage(Long memberId, Long courseId, CourseImageRegisterRequest request) {
		Course course = courseRepository.findByIdAndDeletedAtIsNull(courseId)
			.orElseThrow(() -> new CourseException(CourseErrorCode.COURSE_NOT_FOUND));
		courseAccessPolicy.requireOwner(course, memberId);

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
		courseAccessPolicy.requireOwner(course, memberId);

		CourseImageFile imageFile = courseImageFileRepository.findByCourseId(courseId)
			.orElseThrow(() -> new CourseException(CourseErrorCode.COURSE_IMAGE_NOT_FOUND));

		s3Service.deleteObject(imageFile.getAwsKey());
		courseImageFileRepository.delete(imageFile);
		courseRepository.bumpVersion(courseId);
	}

	public Long forkCourse(Long memberId, Long courseId, String shareToken) {
		Course sourceCourse = courseRepository.findByIdAndDeletedAtIsNull(courseId)
			.orElseThrow(() -> new CourseException(CourseErrorCode.COURSE_NOT_FOUND));

		// 조회 가능하면 포크 가능 — 공개 코스는 누구나, 비공개 코스는 방장과 활성 멤버만, 링크 공개는 토큰이 맞아야.
		// 협업 코스는 PRIVATE 이라 멤버를 빼면 자기가 같이 만든 코스를 챙겨둘 길이 없다.
		// 링크 공개에 토큰을 요구하는 이유: 코스 id 는 순차 정수라 토큰 없이 포크되면 내용을 통째로 가져갈 수 있다.
		if (!courseAccessPolicy.canView(sourceCourse, memberId, shareToken)) {
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
		// 메모는 사람의 것이라 복제하지 않는다 — 가게·순서·일차·방문시간만
		List<CourseStore> copiedStores = sourceStores.stream()
			.map(source -> CourseStore.createWithDetails(
				savedForkedCourse,
				source.getStore(),
				source.getVisitOrder(),
				source.getDay(),
				null,
				source.getVisitTime()))
			.toList();
		courseStoreRepository.saveAll(copiedStores);

		return savedForkedCourse.getId();
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
			// memo 는 비교하지 않는다. 사용자 코스는 course_stores.memo 를 더 이상 쓰지 않아 항상 null == null 로
			// 통과하므로, 넣어두면 판정이 조용히 느슨해진다. 가게·순서·일차·방문시간 네 가지로만 본다.
			if (!Objects.equals(current.getVisitTime(), source.getVisitTime())) {
				return false;
			}
		}
		return true;
	}
}
