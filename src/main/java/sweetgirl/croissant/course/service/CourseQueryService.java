package sweetgirl.croissant.course.service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.course.code.CourseErrorCode;
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
import sweetgirl.croissant.course.exception.CourseException;
import sweetgirl.croissant.course.repository.CourseImageFileRepository;
import sweetgirl.croissant.course.repository.CourseMemberRepository;
import sweetgirl.croissant.course.repository.CourseProgressRepository;
import sweetgirl.croissant.course.repository.CourseRepository;
import sweetgirl.croissant.course.repository.CourseStoreMemoRepository;
import sweetgirl.croissant.course.repository.CourseStoreRepository;
import sweetgirl.croissant.course.repository.CourseStoreVisitRepository;
import sweetgirl.croissant.course.repository.SavedCourseRepository;
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

	public List<CourseSummaryResponse> getCurationCourses() {
		return buildSummaryResponses(courseRepository.findAllPublicOrderedByPinAndSavedCount());
	}

	public List<CourseSummaryResponse> getMyCourses(Long memberId) {
		return buildSummaryResponses(courseRepository
			.findAllByMemberIdAndTypeInAndDeletedAtIsNull(memberId, List.of(CourseType.CUSTOM, CourseType.FORKED)));
	}

	public List<CourseSummaryResponse> getSavedCourses(Long memberId) {
		List<Course> savedCourses = savedCourseRepository.findAllByMemberId(memberId).stream()
			.map(savedCourse -> savedCourse.getCourse())
			.filter(course -> !course.isDeleted() && course.isPublic())
			.toList();
		return buildSummaryResponses(savedCourses);
	}

	public CourseDetailResponse getCourseDetail(Long courseId, Long memberId) {
		Course course = courseRepository.findByIdAndDeletedAtIsNull(courseId)
			.orElseThrow(() -> new CourseException(CourseErrorCode.COURSE_NOT_FOUND));

		// 참여자 목록은 한 번만 읽어 접근 판정·members·메모 정렬에 같이 쓴다. 큐레이션엔 행이 없다.
		List<CourseMember> members = course.getType() == CourseType.CURATED
			? List.of()
			: courseMemberRepository.findAllByIdCourseIdAndDeletedAtIsNullOrderByJoinedAtAsc(courseId);
		boolean isMine = isOwner(course, memberId);
		boolean isMember = !isMine && memberId != null
			&& members.stream().anyMatch(row -> row.getId().getMemberId().equals(memberId));
		// 비공개 코스는 방장과 활성 멤버만 — 협업 코스는 PRIVATE 이라 멤버를 빼면 자기 코스를 못 본다
		if (!course.isPublic() && !isMine && !isMember) {
			throw new CourseException(CourseErrorCode.COURSE_FORBIDDEN);
		}

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
		List<CourseStoreResponse> courseStoreResponses = courseStores.stream()
			.map(courseStore -> {
				List<CourseStoreMemoResponse> memos = memosByCourseStoreId.getOrDefault(courseStore.getId(), List.of());
				String myMemo = memos.stream()
					.filter(CourseStoreMemoResponse::isMine)
					.map(CourseStoreMemoResponse::content)
					.findFirst()
					.orElse(null);
				// 구버전 앱이 읽는 memo: 큐레이션은 새 테이블에 행이 없어 course_stores.memo(코스 설명)를 그대로,
				// 사용자 코스는 내 메모만. legacy 컬럼을 폴백으로 쓰면 멤버가 방장 메모를 자기 것처럼 본다.
				String memo = course.getType() == CourseType.CURATED ? courseStore.getMemo() : myMemo;
				return CourseStoreResponse.of(
					courseStore, visitByStoreId.get(courseStore.getStore().getId()), memo, myMemo, memos);
			})
			.toList();

		CourseImageFile coverImage = courseImageFileRepository.findByCourseId(courseId).orElse(null);

		boolean isSaved = memberId != null && savedCourseRepository.existsByMemberIdAndCourseId(memberId, courseId);
		long savedCount = savedCourseRepository.countByCourseId(courseId);
		long finisherCount = courseProgressRepository.countByCourseIdAndStatus(
			courseId, CourseProgressStatus.FINISHED);

		List<String> regions = regionsOf(courseStores);

		List<CourseMemberResponse> memberResponses = members.stream()
			.map(row -> new CourseMemberResponse(
				row.getId().getMemberId(),
				row.getDisplayName(),
				isOwner(course, row.getId().getMemberId()),
				row.getId().getMemberId().equals(memberId)))
			.toList();
		String myDisplayName = members.stream()
			.filter(row -> row.getId().getMemberId().equals(memberId))
			.map(CourseMember::getDisplayName)
			.findFirst()
			.orElse(null);

		return CourseDetailResponse.of(
			course,
			coverImage,
			courseStoreThumbnailReader.read(courseStores),
			isMine,
			isSaved,
			savedCount,
			finisherCount,
			regions,
			courseStoreResponses,
			memberResponses,
			isMember,
			myDisplayName);
	}

	/**
	 * 폴링 ETag 용. 상세와 같은 접근 판정을 먼저 한다 — 권한이 없는 사람에게 304 로 코스의 존재를 알려주면 안 된다.
	 * 본문이 사람마다 달라도(isMine·visited·myMemo) ETag 는 같아도 된다: A 의 304 는 A 의 이전 응답을 다시 쓰라는 뜻이다.
	 */
	public long getVersion(Long courseId, Long memberId) {
		Course course = courseRepository.findByIdAndDeletedAtIsNull(courseId)
			.orElseThrow(() -> new CourseException(CourseErrorCode.COURSE_NOT_FOUND));
		if (!course.isPublic() && !isOwner(course, memberId) && !isActiveMember(course, memberId)) {
			throw new CourseException(CourseErrorCode.COURSE_FORBIDDEN);
		}
		return course.getVersion();
	}

	private boolean isOwner(Course course, Long memberId) {
		return memberId != null && course.getMember() != null && course.getMember().getId().equals(memberId);
	}

	private boolean isActiveMember(Course course, Long memberId) {
		return memberId != null
			&& courseMemberRepository.existsByIdCourseIdAndIdMemberIdAndDeletedAtIsNull(course.getId(), memberId);
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
		Map<Long, Integer> joinOrderByMemberId = new java.util.HashMap<>();
		Map<Long, String> displayNameByMemberId = new java.util.HashMap<>();
		for (int i = 0; i < members.size(); i++) {
			joinOrderByMemberId.put(members.get(i).getId().getMemberId(), i);
			displayNameByMemberId.put(members.get(i).getId().getMemberId(), members.get(i).getDisplayName());
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

	/** 코스별 가게·커버 이미지를 일괄 조회해 코스 수와 무관하게 고정된 쿼리 수로 summary를 만든다 */
	private List<CourseSummaryResponse> buildSummaryResponses(List<Course> courses) {
		if (courses.isEmpty()) {
			return List.of();
		}
		List<Long> courseIds = courses.stream().map(Course::getId).toList();

		Map<Long, List<CourseStore>> courseStoresByCourseId = courseStoreRepository
			.findAllByCourseIdInAndDeletedAtIsNullOrderByVisitOrderAsc(courseIds)
			.stream()
			.collect(Collectors.groupingBy(courseStore -> courseStore.getCourse().getId()));

		Map<Long, CourseImageFile> coverImageByCourseId = courseImageFileRepository.findAllByCourseIdIn(courseIds)
			.stream()
			.collect(Collectors.toMap(
				imageFile -> imageFile.getCourse().getId(),
				imageFile -> imageFile,
				(first, second) -> first));

		Map<Long, Long> savedCountByCourseId = findSavedCountByCourseId(courses);
		Map<Long, Long> finisherCountByCourseId = findFinisherCountByCourseId(courseIds);
		Map<Long, List<String>> storeThumbnailsByCourseId = courseStoreThumbnailReader
			.readByCourse(courseStoresByCourseId);

		return courses.stream()
			.map(course -> {
				List<CourseStore> courseStores = courseStoresByCourseId.getOrDefault(course.getId(), List.of());
				List<String> regions = regionsOf(courseStores);
				return CourseSummaryResponse.of(
					course,
					coverImageByCourseId.get(course.getId()),
					storeThumbnailsByCourseId.getOrDefault(course.getId(), List.of()),
					storeNamesOf(courseStores),
					courseStores.size(),
					storeIdsOf(courseStores),
					regions,
					savedCountByCourseId.getOrDefault(course.getId(), 0L),
					finisherCountByCourseId.getOrDefault(course.getId(), 0L));
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
	private List<Long> storeIdsOf(List<CourseStore> courseStores) {
		return courseStores.stream()
			.map(courseStore -> courseStore.getStore().getId())
			.toList();
	}

	/**
	 * 목록에 세울 가게 이름 앞 세 개. 방문 순서를 그대로 따른다.
	 *
	 * 빵집 사진은 서로 비슷해서 어떤 코스인지 말해 주지 못하는데, 아는 가게 이름 하나가
	 * 그 일을 한다. regionsOf가 이미 Store를 읽고 있어 여기서 이름을 더 읽는 건 공짜다.
	 */
	private List<String> storeNamesOf(List<CourseStore> courseStores) {
		return courseStores.stream()
			.limit(SUMMARY_STORE_NAME_LIMIT)
			.map(courseStore -> courseStore.getStore().getName())
			.filter(name -> name != null)
			.toList();
	}

	/** 코스 가게들의 지역을 "시(축약) 구"(예: "대전 중구") 목록으로. 순서 유지·중복 제거. */
	private List<String> regionsOf(List<CourseStore> courseStores) {
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
