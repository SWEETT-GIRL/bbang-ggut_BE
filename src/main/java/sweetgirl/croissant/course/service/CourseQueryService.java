package sweetgirl.croissant.course.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongPredicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.course.code.CourseErrorCode;
import sweetgirl.croissant.course.dto.projection.CourseStoreSummaryProjection;
import sweetgirl.croissant.course.dto.response.CourseDetailLookup;
import sweetgirl.croissant.course.dto.response.CourseDetailResponse;
import sweetgirl.croissant.course.dto.response.CourseMemberResponse;
import sweetgirl.croissant.course.dto.response.CourseStoreMemoResponse;
import sweetgirl.croissant.course.dto.response.CourseStoreResponse;
import sweetgirl.croissant.course.dto.response.CourseSummaryResponse;
import sweetgirl.croissant.course.entity.Course;
import sweetgirl.croissant.course.entity.CourseImageFile;
import sweetgirl.croissant.course.entity.CourseMember;
import sweetgirl.croissant.course.entity.CourseStore;
import sweetgirl.croissant.course.entity.CourseStoreMemo;
import sweetgirl.croissant.course.entity.CourseStoreVisit;
import sweetgirl.croissant.course.enums.CourseProgressStatus;
import sweetgirl.croissant.course.enums.CourseType;
import sweetgirl.croissant.course.enums.CourseVisibility;
import sweetgirl.croissant.course.exception.CourseException;
import sweetgirl.croissant.course.repository.CourseImageFileRepository;
import sweetgirl.croissant.course.repository.CourseMemberRepository;
import sweetgirl.croissant.course.repository.CourseProgressRepository;
import sweetgirl.croissant.course.repository.CourseRepository;
import sweetgirl.croissant.course.repository.CourseStoreMemoRepository;
import sweetgirl.croissant.course.repository.CourseStoreRepository;
import sweetgirl.croissant.course.repository.CourseStoreVisitRepository;
import sweetgirl.croissant.course.repository.SavedCourseRepository;
import sweetgirl.croissant.reward.service.CrumbGrantService;
import sweetgirl.croissant.reward.service.TourRewardService;
import sweetgirl.croissant.store.RegionFormatter;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CourseQueryService {

	/** 목록 한 줄에 들어가는 가게 이름 개수. 세 개를 넘기면 작은 기기에서 잘린다. */
	private static final int SUMMARY_STORE_NAME_LIMIT = 3;

	private final CourseRepository courseRepository;
	private final CourseStoreRepository courseStoreRepository;
	private final SavedCourseRepository savedCourseRepository;
	private final CourseImageFileRepository courseImageFileRepository;
	private final CourseProgressRepository courseProgressRepository;
	private final CourseStoreThumbnailReader courseStoreThumbnailReader;
	private final CourseStoreVisitRepository courseStoreVisitRepository;
	private final CourseStoreMemoRepository courseStoreMemoRepository;
	private final CourseMemberRepository courseMemberRepository;
	private final CourseAccessPolicy courseAccessPolicy;
	private final TourRewardService tourRewardService;

	public List<CourseSummaryResponse> getCurationCourses() {
		return buildSummaryResponses(courseRepository.findAllPublicOrderedByPinAndSavedCount(), Set.of());
	}

	/**
	 * 내 빵투어 = 내가 만든 코스 ∪ 참여 중인 코스. 소유·참여를 섞어 최근 수정순으로 내려준다.
	 * 방장도 course_members 행을 가지므로 협업 중인 내 코스는 양쪽에 다 잡힌다 — courseId 로 dedup 한다.
	 * bumpVersion 이 updated_at 도 올리므로 가게·메모·멤버 변경이 이 정렬에 잡힌다.
	 */
	public List<CourseSummaryResponse> getMyCourses(Long memberId) {
		List<Course> owned = courseRepository
			.findAllByMemberIdAndTypeInAndDeletedAtIsNull(memberId, List.of(CourseType.CUSTOM, CourseType.FORKED));
		List<Course> joined = courseMemberRepository.findAllByIdMemberIdAndDeletedAtIsNull(memberId).stream()
			.map(CourseMember::getCourse)
			.filter(course -> !course.isDeleted())
			.toList();
		Set<Long> memberCourseIds = joined.stream()
			.filter(course -> !courseAccessPolicy.isOwner(course, memberId))
			.map(Course::getId)
			.collect(Collectors.toSet());

		Map<Long, Course> courseById = new LinkedHashMap<>();
		Stream.concat(owned.stream(), joined.stream())
			.forEach(course -> courseById.putIfAbsent(course.getId(), course));
		List<Course> merged = courseById.values().stream()
			.sorted(Comparator.comparing(Course::getUpdatedAt, Comparator.nullsLast(
				Comparator.<LocalDateTime>reverseOrder()))
				.thenComparing(Course::getId, Comparator.reverseOrder()))
			.toList();
		return buildSummaryResponses(merged, memberCourseIds);
	}

	public List<CourseSummaryResponse> getSavedCourses(Long memberId) {
		// 참여하면 찜이 풀리지만(수락 때 삭제) 그 전 데이터가 남아 있을 수 있다 — 참여 중인 코스는 목록에서 뺀다
		Set<Long> joinedCourseIds = Set.copyOf(courseMemberRepository.findActiveCourseIdsByMemberId(memberId));
		List<Course> savedCourses = savedCourseRepository.findAllByMemberId(memberId).stream()
			.map(savedCourse -> savedCourse.getCourse())
			.filter(course -> !course.isDeleted() && course.isPublic())
			.filter(course -> !joinedCourseIds.contains(course.getId()))
			.toList();
		return buildSummaryResponses(savedCourses, Set.of());
	}

	/**
	 * 코스 상세. 판정 순서 — PRIVATE: 방장·활성 멤버만(토큰이 맞아도 403, 토큰보다 PRIVATE 이 강하다) /
	 * UNLISTED: 방장·활성 멤버 이거나 t 가 share_token 과 일치 / PUBLIC: 누구나.
	 */
	public CourseDetailResponse getCourseDetail(Long courseId, Long memberId, String shareToken) {
		return getCourseDetail(courseId, memberId, shareToken, version -> false).body().orElseThrow();
	}

	/**
	 * 조건부 GET 용. 접근 판정을 먼저 하고(권한 없는 사람에게 304 로 코스의 존재를 알려주면 안 된다) 현재 version 을
	 * notModified 에 넘긴다 — true 면 본문을 만들지 않는다. 코스·참여자 목록은 판정과 조립이 같이 쓰므로 한 번만 읽는다.
	 * 본문이 사람마다 달라도(isMine·visited·myMemo) ETag 는 version 하나로 충분하다: A 의 304 는 A 의 이전 응답을 다시 쓰라는 뜻이다.
	 */
	public CourseDetailLookup getCourseDetail(Long courseId, Long memberId, String shareToken,
			LongPredicate notModified) {
		Course course = courseRepository.findByIdAndDeletedAtIsNull(courseId)
			.orElseThrow(() -> new CourseException(CourseErrorCode.COURSE_NOT_FOUND));
		// 비공개 코스는 방장과 활성 멤버만 — 협업 코스는 PRIVATE 이라 멤버를 빼면 자기 코스를 못 본다.
		// 판정은 exists 로 끝낸다 — 참여자 목록은 본문을 만들 때만 읽어 304 폴링이 목록까지 읽지 않게 한다
		courseAccessPolicy.requireViewable(course, memberId, shareToken);
		if (notModified.test(course.getVersion())) {
			return CourseDetailLookup.notModified(course.getVersion());
		}
		List<CourseMember> members = activeMembersOf(course, courseId);
		boolean isMine = courseAccessPolicy.isOwner(course, memberId);
		boolean isMember = isActiveMemberIn(members, memberId, isMine);
		return CourseDetailLookup.of(buildDetail(course, courseId, memberId, members, isMine, isMember));
	}

	/**
	 * 랜딩이 /c/{shareToken} 으로 받으면 코스 id 를 모른다 — 토큰만으로 찾는 입구.
	 * 토큰은 맞는데 지금은 UNLISTED 가 아닌 코스는 403 이 아니라 404 — "토큰은 맞다"를 알려주면 토큰 유효성이 샌다.
	 */
	public CourseDetailResponse getCourseDetailByShareToken(String shareToken, Long memberId) {
		Course course = courseRepository.findByShareTokenAndDeletedAtIsNull(shareToken)
			.filter(found -> found.getVisibility() == CourseVisibility.UNLISTED)
			.orElseThrow(() -> new CourseException(CourseErrorCode.COURSE_NOT_FOUND));
		List<CourseMember> members = activeMembersOf(course, course.getId());
		boolean isMine = courseAccessPolicy.isOwner(course, memberId);
		boolean isMember = isActiveMemberIn(members, memberId, isMine);
		return buildDetail(course, course.getId(), memberId, members, isMine, isMember);
	}

	/** 참여자 목록은 한 번만 읽어 접근 판정·members·메모 정렬에 같이 쓴다. 큐레이션엔 행이 없다. */
	private List<CourseMember> activeMembersOf(Course course, Long courseId) {
		return course.getType() == CourseType.CURATED
			? List.of()
			: courseMemberRepository.findAllByIdCourseIdAndDeletedAtIsNullOrderByJoinedAtAsc(courseId);
	}

	private boolean isActiveMemberIn(List<CourseMember> members, Long memberId, boolean isMine) {
		return !isMine && memberId != null
			&& members.stream().anyMatch(row -> row.getId().getMemberId().equals(memberId));
	}

	private CourseDetailResponse buildDetail(Course course, Long courseId, Long memberId, List<CourseMember> members,
			boolean isMine, boolean isMember) {
		List<CourseStore> courseStores = courseStoreRepository
			.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(courseId);
		// 방문 표시는 사람마다 다르다. 비로그인이면 아무것도 표시되지 않는다.
		Map<Long, CourseStoreVisit> visitByStoreId = memberId == null
			? Map.of()
			: courseStoreVisitRepository
				.findAllByIdCourseIdAndIdMemberIdAndDeletedAtIsNull(courseId, memberId).stream()
				.collect(Collectors.toMap(visit -> visit.getId().getStoreId(), visit -> visit, (a, b) -> a));

		Map<Long, List<CourseStoreMemoResponse>> memosByCourseStoreId = memosOf(course, courseStores, members,
			memberId);
		List<CourseStoreResponse> courseStoreResponses = toStoreResponses(
			course, courseStores, visitByStoreId, memosByCourseStoreId,
			visitRewardableStoreIds(course, courseStores, visitByStoreId, memberId));

		CourseImageFile coverImage = courseImageFileRepository.findByCourseId(courseId).orElse(null);
		boolean isSaved = memberId != null && savedCourseRepository.existsByMemberIdAndCourseId(memberId, courseId);
		long savedCount = savedCourseRepository.countByCourseId(courseId);
		long finisherCount = courseProgressRepository.countByCourseIdAndStatus(
			courseId, CourseProgressStatus.FINISHED);
		// 공유 토큰은 방장에게만, 링크 공개일 때만 — 멤버·토큰으로 들어온 사람에겐 내려주지 않는다
		String shareTokenForViewer = isMine && course.getVisibility() == CourseVisibility.UNLISTED
			? course.getShareToken()
			: null;

		return CourseDetailResponse.of(
			course,
			coverImage,
			courseStoreThumbnailReader.read(courseStores),
			isMine,
			isSaved,
			savedCount,
			finisherCount,
			detailRegionsOf(courseStores),
			courseStoreResponses,
			toMemberResponses(course, members, memberId),
			isMember,
			myDisplayNameOf(members, memberId),
			shareTokenForViewer);
	}

	/**
	 * 지금 다녀왔다고 표시하면 다녀옴 보상을 받을 가게들. 방문 체크는 토큰 없이 볼 수 있어야 하므로(링크로만 들어온 제3자는 못 한다)
	 * 그 권한이 없으면 비어 있고, 이미 표시한 가게는 다시 눌러도 보상이 없어 뺀다.
	 */
	private Set<Long> visitRewardableStoreIds(Course course, List<CourseStore> courseStores,
			Map<Long, CourseStoreVisit> visitByStoreId, Long memberId) {
		if (memberId == null || !courseAccessPolicy.canView(course, memberId, null)) {
			return Set.of();
		}
		List<Long> unvisitedStoreIds = courseStores.stream()
			.map(courseStore -> courseStore.getStore().getId())
			.filter(storeId -> !visitByStoreId.containsKey(storeId))
			.distinct()
			.toList();
		return tourRewardService.visitRewardableStoreIds(memberId, course, unvisitedStoreIds,
			LocalDate.now(CrumbGrantService.ZONE));
	}

	/**
	 * 정류장 응답. 구버전 앱이 읽는 memo 는 큐레이션이면 course_stores.memo(코스 설명) 그대로, 사용자 코스면 내 메모만 —
	 * legacy 컬럼을 폴백으로 쓰면 멤버가 방장 메모를 자기 것처럼 본다.
	 */
	private List<CourseStoreResponse> toStoreResponses(Course course, List<CourseStore> courseStores,
			Map<Long, CourseStoreVisit> visitByStoreId, Map<Long, List<CourseStoreMemoResponse>> memosByCourseStoreId,
			Set<Long> visitRewardableStoreIds) {
		return courseStores.stream()
			.map(courseStore -> {
				List<CourseStoreMemoResponse> memos = memosByCourseStoreId.getOrDefault(courseStore.getId(), List.of());
				String myMemo = memos.stream()
					.filter(CourseStoreMemoResponse::isMine)
					.map(CourseStoreMemoResponse::content)
					.findFirst()
					.orElse(null);
				String memo = course.getType() == CourseType.CURATED ? courseStore.getMemo() : myMemo;
				Long storeId = courseStore.getStore().getId();
				return CourseStoreResponse.of(courseStore, visitByStoreId.get(storeId), memo, myMemo, memos,
					visitRewardableStoreIds.contains(storeId));
			})
			.toList();
	}

	private List<CourseMemberResponse> toMemberResponses(Course course, List<CourseMember> members, Long viewerId) {
		return members.stream()
			.map(row -> new CourseMemberResponse(
				row.getId().getMemberId(),
				row.getMember().getNickname(),
				courseAccessPolicy.isOwner(course, row.getId().getMemberId()),
				row.getId().getMemberId().equals(viewerId)))
			.toList();
	}

	private String myDisplayNameOf(List<CourseMember> members, Long viewerId) {
		return members.stream()
			.filter(row -> row.getId().getMemberId().equals(viewerId))
			.map(row -> row.getMember().getNickname())
			.findFirst()
			.orElse(null);
	}

	/**
	 * 정류장별 사람별 메모. 메모 한 방으로 읽고 이미 읽어둔 멤버 목록으로 정렬한다 — 정류장마다 돌지 않는다.
	 * 정렬은 서버가 참여 순서(course_members.joined_at)로 하고, 방장은 첫 초대 때 행이 생기므로 항상 맨 앞이다.
	 * course_members 행이 없는 사람(이관 전 옛 데이터)은 닉네임으로 폴백하고 맨 뒤에 둔다.
	 * 큐레이션 코스는 이 테이블을 쓰지 않으므로 읽지 않는다.
	 */
	private Map<Long, List<CourseStoreMemoResponse>> memosOf(Course course, List<CourseStore> courseStores,
			List<CourseMember> members, Long viewerId) {
		if (course.getType() == CourseType.CURATED || courseStores.isEmpty()) {
			return Map.of();
		}
		List<Long> courseStoreIds = courseStores.stream().map(CourseStore::getId).toList();
		List<CourseStoreMemo> memos = courseStoreMemoRepository
			.findAllByIdCourseStoreIdInAndDeletedAtIsNull(courseStoreIds);
		if (memos.isEmpty()) {
			return Map.of();
		}
		Map<Long, Integer> joinOrderByMemberId = new HashMap<>();
		Map<Long, String> displayNameByMemberId = new HashMap<>();
		for (int i = 0; i < members.size(); i++) {
			joinOrderByMemberId.put(members.get(i).getId().getMemberId(), i);
			displayNameByMemberId.put(members.get(i).getId().getMemberId(), members.get(i).getMember().getNickname());
		}
		Comparator<CourseStoreMemo> byJoinOrder = Comparator
			.comparing((CourseStoreMemo memo) -> joinOrderByMemberId.getOrDefault(memo.getId().getMemberId(),
				Integer.MAX_VALUE))
			.thenComparing(memo -> memo.getId().getMemberId());
		return memos.stream()
			.sorted(byJoinOrder)
			.collect(Collectors.groupingBy(
				memo -> memo.getId().getCourseStoreId(),
				Collectors.mapping(memo -> new CourseStoreMemoResponse(
					memo.getId().getMemberId(),
					displayNameByMemberId.getOrDefault(memo.getId().getMemberId(), memo.getMember().getNickname()),
					memo.getContent(),
					memo.getUpdatedAt(),
					Objects.equals(memo.getId().getMemberId(), viewerId)), Collectors.toList())));
	}

	/**
	 * 코스별 가게·커버 이미지를 일괄 조회해 코스 수와 무관하게 고정된 쿼리 수로 summary를 만든다.
	 *
	 * @param memberCourseIds 방장 아닌 활성 멤버로 참여 중인 코스 id — isMember('함께' 배지). 내 목록 말고는 비어 있다
	 */
	private List<CourseSummaryResponse> buildSummaryResponses(List<Course> courses, Set<Long> memberCourseIds) {
		if (courses.isEmpty()) {
			return List.of();
		}
		List<Long> courseIds = courses.stream().map(Course::getId).toList();

		// 목록은 가게 id·이름·지역만 쓴다 — Store 엔티티 대신 프로젝션으로 읽어 geography 위치 같은 컬럼을 싣지 않는다
		Map<Long, List<CourseStoreSummaryProjection>> stopsByCourseId = courseStoreRepository
			.findSummariesByCourseIdIn(courseIds)
			.stream()
			.collect(Collectors.groupingBy(CourseStoreSummaryProjection::getCourseId));

		Map<Long, CourseImageFile> coverImageByCourseId = courseImageFileRepository.findAllByCourseIdIn(courseIds)
			.stream()
			.collect(Collectors.toMap(
				imageFile -> imageFile.getCourse().getId(),
				imageFile -> imageFile,
				(first, second) -> first));

		Map<Long, Long> savedCountByCourseId = findSavedCountByCourseId(courses);
		Map<Long, Long> finisherCountByCourseId = findFinisherCountByCourseId(courseIds);
		Map<Long, List<Long>> storeIdsByCourseId = stopsByCourseId.entrySet().stream()
			.collect(Collectors.toMap(Map.Entry::getKey, entry -> storeIdsOf(entry.getValue())));
		Map<Long, List<String>> storeThumbnailsByCourseId = courseStoreThumbnailReader
			.readByCourse(storeIdsByCourseId);

		return courses.stream()
			.map(course -> {
				List<CourseStoreSummaryProjection> stops = stopsByCourseId.getOrDefault(course.getId(), List.of());
				return CourseSummaryResponse.of(
					course,
					coverImageByCourseId.get(course.getId()),
					storeThumbnailsByCourseId.getOrDefault(course.getId(), List.of()),
					storeNamesOf(stops),
					stops.size(),
					storeIdsByCourseId.getOrDefault(course.getId(), List.of()),
					regionsOf(stops),
					savedCountByCourseId.getOrDefault(course.getId(), 0L),
					finisherCountByCourseId.getOrDefault(course.getId(), 0L),
					memberCourseIds.contains(course.getId()));
			})
			.toList();
	}

	private Map<Long, Long> findFinisherCountByCourseId(List<Long> courseIds) {
		return courseProgressRepository.countCompletedGroupedByCourseIdIn(courseIds).stream()
			.collect(Collectors.toMap(
				courseIdAndCount -> (Long)courseIdAndCount[0],
				courseIdAndCount -> (Long)courseIdAndCount[1]));
	}

	/**
	 * 코스에 담긴 가게 ID 목록. 방문 순서를 그대로 따른다.
	 *
	 * 이미 담긴 코스를 클라이언트가 알아야 하는데(가게 상세의 '빵투어에 담기'), 이게 없으면
	 * 코스마다 상세를 한 번씩 부르게 된다. 여기선 courseStores를 이미 일괄 조회해 둔 터라 공짜다.
	 */
	private List<Long> storeIdsOf(List<CourseStoreSummaryProjection> stops) {
		return stops.stream()
			.map(CourseStoreSummaryProjection::getStoreId)
			.toList();
	}

	/**
	 * 목록에 세울 가게 이름 앞 세 개. 방문 순서를 그대로 따른다.
	 *
	 * 빵집 사진은 서로 비슷해서 어떤 코스인지 말해 주지 못하는데, 아는 가게 이름 하나가
	 * 그 일을 한다. regionsOf가 이미 Store를 읽고 있어 여기서 이름을 더 읽는 건 공짜다.
	 */
	private List<String> storeNamesOf(List<CourseStoreSummaryProjection> stops) {
		return stops.stream()
			.limit(SUMMARY_STORE_NAME_LIMIT)
			.map(CourseStoreSummaryProjection::getStoreName)
			.filter(name -> name != null)
			.toList();
	}

	/** 목록용 — 코스 가게들의 지역을 "시(축약) 구"(예: "대전 중구") 목록으로. 순서 유지·중복 제거. */
	private List<String> regionsOf(List<CourseStoreSummaryProjection> stops) {
		return stops.stream()
			.map(stop -> RegionFormatter.cityDistrict(stop.getRegion1st(), stop.getRegion2nd()))
			.filter(region -> region != null)
			.distinct()
			.toList();
	}

	/** 상세용 — 정류장 엔티티에서 같은 지역 목록을 만든다. 상세는 한 코스라 Store 는 배치 페치로 읽는다. */
	private List<String> detailRegionsOf(List<CourseStore> courseStores) {
		return courseStores.stream()
			.map(courseStore -> RegionFormatter.cityDistrict(
				courseStore.getStore().getRegion1st(),
				courseStore.getStore().getRegion2nd()))
			.filter(region -> region != null)
			.distinct()
			.toList();
	}

	private Map<Long, Long> findSavedCountByCourseId(List<Course> courses) {
		if (courses.isEmpty()) {
			return Map.of();
		}
		List<Long> courseIds = courses.stream().map(Course::getId).toList();
		return savedCourseRepository.countGroupedByCourseIdIn(courseIds).stream()
			.collect(Collectors.toMap(
				courseIdAndCount -> (Long)courseIdAndCount[0],
				courseIdAndCount -> (Long)courseIdAndCount[1]));
	}
}
