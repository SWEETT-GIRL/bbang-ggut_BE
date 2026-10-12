package sweetgirl.croissant.course.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.course.code.CourseErrorCode;
import sweetgirl.croissant.course.dto.projection.CourseProgressAggregateProjection;
import sweetgirl.croissant.course.dto.projection.CourseProgressMemberAggregateProjection;
import sweetgirl.croissant.course.dto.projection.CourseStoreSummaryProjection;
import sweetgirl.croissant.course.dto.response.CourseProgressResponse;
import sweetgirl.croissant.course.entity.Course;
import sweetgirl.croissant.course.entity.CourseImageFile;
import sweetgirl.croissant.course.entity.CourseProgress;
import sweetgirl.croissant.course.entity.CourseStoreVisit;
import sweetgirl.croissant.course.enums.CourseProgressStatus;
import sweetgirl.croissant.course.exception.CourseException;
import sweetgirl.croissant.course.repository.CourseImageFileRepository;
import sweetgirl.croissant.course.repository.CourseProgressRepository;
import sweetgirl.croissant.course.repository.CourseRepository;
import sweetgirl.croissant.course.repository.CourseStoreRepository;
import sweetgirl.croissant.course.repository.CourseStoreVisitRepository;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.member.service.MemberRestrictionReader;

@Service
@RequiredArgsConstructor
@Transactional
public class CourseProgressService {

	private final MemberReader memberReader;
	private final MemberRestrictionReader memberRestrictionReader;
	private final CourseRepository courseRepository;
	private final CourseProgressRepository courseProgressRepository;
	private final CourseImageFileRepository courseImageFileRepository;
	private final CourseStoreRepository courseStoreRepository;
	private final CourseStoreThumbnailReader courseStoreThumbnailReader;
	private final CourseStoreVisitRepository courseStoreVisitRepository;
	private final CourseAccessPolicy courseAccessPolicy;

	public void start(Long memberId, Long courseId) {
		Member member = memberReader.getMember(memberId);
		memberRestrictionReader.validateCanWrite(memberId);
		Course course = getAccessibleCourse(courseId, memberId);
		courseProgressRepository.findByMemberIdAndCourseId(memberId, courseId)
			.ifPresentOrElse(
				CourseProgress::resume,
				() -> courseProgressRepository.save(CourseProgress.start(member, course, LocalDateTime.now())));
		refresh(memberId);
	}

	public void stop(Long memberId, Long courseId) {
		memberReader.getMember(memberId);
		courseProgressRepository.findByMemberIdAndCourseId(memberId, courseId)
			.ifPresent(progress -> progress.stop(LocalDateTime.now()));
	}

	public List<CourseProgressResponse> getOngoing(Long memberId) {
		memberReader.getMember(memberId);
		List<CourseProgress> progresses = refresh(memberId);
		return buildResponses(memberId, progresses, CourseProgressStatus.IN_PROGRESS);
	}

	public List<CourseProgressResponse> getByStatus(Long memberId, CourseProgressStatus status) {
		memberReader.getMember(memberId);
		List<CourseProgress> progresses = status == CourseProgressStatus.IN_PROGRESS
			? refresh(memberId)
			: courseProgressRepository.findAllByMemberIdAndStatus(memberId, status);
		return buildResponses(memberId, progresses, status);
	}

	private List<CourseProgressResponse> buildResponses(
			Long memberId,
			List<CourseProgress> progresses,
			CourseProgressStatus status) {
		progresses = progresses.stream()
			.filter(progress -> progress.getStatus() == status)
			.toList();
		if (progresses.isEmpty()) {
			return List.of();
		}

		Set<Long> ongoingCourseIds = progresses.stream()
			.map(progress -> progress.getCourse().getId())
			.collect(Collectors.toCollection(LinkedHashSet::new));
		if (ongoingCourseIds.isEmpty()) {
			return List.of();
		}
		List<Long> courseIds = List.copyOf(ongoingCourseIds);
		Map<Long, CourseProgressAggregateProjection> aggregateByCourseId = findAggregates(memberId, courseIds);
		Map<Long, CourseImageFile> imageByCourseId = courseImageFileRepository.findAllByCourseIdIn(courseIds)
			.stream()
			.collect(Collectors.toMap(
				image -> image.getCourse().getId(),
				Function.identity(),
				(first, second) -> first));
		// 진행 카드는 가게 썸네일만 필요하다 — Store 엔티티가 아니라 가게 id 만 코스별로 모은다
		Map<Long, List<Long>> storeIdsByCourseId = courseStoreRepository.findSummariesByCourseIdIn(courseIds).stream()
			.collect(Collectors.groupingBy(
				CourseStoreSummaryProjection::getCourseId,
				Collectors.mapping(CourseStoreSummaryProjection::getStoreId, Collectors.toList())));
		Map<Long, List<String>> storeThumbnailsByCourseId = courseStoreThumbnailReader
			.readByCourse(storeIdsByCourseId);

		return progresses.stream()
			.filter(progress -> ongoingCourseIds.contains(progress.getCourse().getId()))
			.map(progress -> toResponse(
				progress,
				imageByCourseId.get(progress.getCourse().getId()),
				storeThumbnailsByCourseId.getOrDefault(progress.getCourse().getId(), List.of()),
				aggregateByCourseId.get(progress.getCourse().getId())))
			.sorted(Comparator.comparing(this::lastActivityAt).reversed())
			.toList();
	}

	/**
	 * 완주 여부를 다시 본다.
	 *
	 * @return 이번 호출로 "진행 중 → 완주"가 된 빵투어들. 완주는 되돌아가지 않으므로 한 빵투어는 한 번만 여기 담긴다
	 */
	public List<Long> refreshForMember(Long memberId) {
		return refresh(memberId).stream()
			.filter(progress -> progress.getStatus() == CourseProgressStatus.FINISHED)
			.map(progress -> progress.getCourse().getId())
			.toList();
	}

	/**
	 * 방문을 표시하는 회원의 진행 행을 보장하고, 처음 다녀온 날을 남긴다.
	 * "시작하기"를 누르지 않고 바로 다녀왔다고 표시해도 진행과 완주가 따라오게 한다. 멈춰 둔 빵투어는 다시 이어진다.
	 */
	public CourseProgress ensureStartedForVisit(Member member, Course course, LocalDate visitedOn) {
		CourseProgress progress = courseProgressRepository.findByMemberIdAndCourseId(member.getId(), course.getId())
			.orElseGet(() -> courseProgressRepository.save(CourseProgress.start(member, course, LocalDateTime.now())));
		progress.resume();
		progress.markFirstVisit(visitedOn);
		return progress;
	}

	/**
	 * 기록을 지우면 그 기록으로 켜둔 코스 방문 체크도 함께 끈다.
	 *
	 * 방문을 별도 테이블로 옮기기 전에는 방문이 기록에서 파생됐기 때문에
	 * 기록을 지우면 체크가 저절로 풀렸다. 그 동작을 그대로 유지한다.
	 */
	public void detachVisitsByRecord(Long breadRecordId) {
		courseStoreVisitRepository.findAllByBreadRecordIdAndDeletedAtIsNull(breadRecordId)
			.forEach(CourseStoreVisit::softDelete);
	}

	/**
	 * 코스의 가게가 바뀌었을 때 진행 중인 참여자 전원의 완주 여부를 다시 본다.
	 *
	 * @param allowPromotion 가게가 늘어난 경우에만 true. 삭제로 storeCount 가 줄면 방문 수는 그대로인 채
	 *                       분모만 작아져 가지 않은 코스가 완주 처리된다(되돌리는 경로가 없어 뱃지까지 나간다).
	 *                       그때는 false — 승격 없이 끝난다
	 */
	public void refreshForCourse(Long courseId, boolean allowPromotion) {
		List<CourseProgress> progresses = courseProgressRepository.findAllByCourseIdAndStatus(
			courseId, CourseProgressStatus.IN_PROGRESS);
		if (progresses.isEmpty()) {
			return;
		}
		Map<Long, CourseProgressMemberAggregateProjection> aggregateByMemberId = courseProgressRepository
			.findMemberAggregates(courseId)
			.stream()
			.collect(Collectors.toMap(CourseProgressMemberAggregateProjection::getMemberId, Function.identity()));
		LocalDateTime completedAt = LocalDateTime.now();
		progresses.forEach(progress -> {
			CourseProgressMemberAggregateProjection aggregate = aggregateByMemberId.get(progress.getMember().getId());
			updateCompletion(
				progress,
				aggregate == null ? 0 : aggregate.getStoreCount(),
				aggregate == null ? 0 : aggregate.getVisitedStoreCount(),
				completedAt,
				allowPromotion);
		});
	}

	private List<CourseProgress> refresh(Long memberId) {
		List<CourseProgress> progresses = courseProgressRepository.findAllByMemberIdAndStatus(
			memberId, CourseProgressStatus.IN_PROGRESS);
		if (progresses.isEmpty()) {
			return progresses;
		}
		List<Long> courseIds = progresses.stream().map(progress -> progress.getCourse().getId()).toList();
		Map<Long, CourseProgressAggregateProjection> aggregateByCourseId = findAggregates(memberId, courseIds);
		LocalDateTime completedAt = LocalDateTime.now();
		progresses.forEach(progress -> {
			CourseProgressAggregateProjection aggregate = aggregateByCourseId.get(progress.getCourse().getId());
			updateCompletion(
				progress,
				aggregate == null ? 0 : aggregate.getStoreCount(),
				aggregate == null ? 0 : aggregate.getVisitedStoreCount(),
				completedAt,
				true);
		});
		return progresses;
	}

	/** 완주는 단방향이다 — 되돌리는 경로가 없다. 승격이 막힌 호출이면 아무것도 하지 않는다. */
	private void updateCompletion(
			CourseProgress progress,
			long storeCount,
			long visitedStoreCount,
			LocalDateTime completedAt,
			boolean allowPromotion) {
		if (!allowPromotion) {
			return;
		}
		if (storeCount > 0 && visitedStoreCount >= storeCount) {
			progress.markFinished(completedAt);
		}
	}

	private Map<Long, CourseProgressAggregateProjection> findAggregates(Long memberId, List<Long> courseIds) {
		return courseProgressRepository.findAggregates(memberId, courseIds).stream()
			.collect(Collectors.toMap(CourseProgressAggregateProjection::getCourseId, Function.identity()));
	}

	private CourseProgressResponse toResponse(
			CourseProgress progress,
			CourseImageFile image,
			List<String> storeThumbnailKeys,
			CourseProgressAggregateProjection aggregate) {
		int storeCount = aggregate == null ? 0 : Math.toIntExact(aggregate.getStoreCount());
		int visitedStoreCount = aggregate == null ? 0 : Math.toIntExact(aggregate.getVisitedStoreCount());
		return CourseProgressResponse.of(
			progress,
			image,
			storeThumbnailKeys,
			storeCount,
			visitedStoreCount,
			aggregate == null ? null : aggregate.getLastTouchedAt());
	}

	private LocalDateTime lastActivityAt(CourseProgressResponse response) {
		return response.lastTouchedAt() != null ? response.lastTouchedAt() : response.startedAt();
	}

	/** 공개 코스는 누구나, 비공개 코스는 방장과 활성 멤버만 따라갈 수 있다 — 협업 코스는 PRIVATE 이라 멤버를 빼면 시작조차 못 한다. */
	private Course getAccessibleCourse(Long courseId, Long memberId) {
		Course course = courseRepository.findByIdAndDeletedAtIsNull(courseId)
			.orElseThrow(() -> new CourseException(CourseErrorCode.COURSE_NOT_FOUND));
		// 토큰 없이 — 링크 공개 코스라도 제3자는 따라가지 못한다
		courseAccessPolicy.requireViewable(course, memberId, null);
		return course;
	}
}
