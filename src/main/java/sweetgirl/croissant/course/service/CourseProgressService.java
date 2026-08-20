package sweetgirl.croissant.course.service;

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
import sweetgirl.croissant.course.dto.response.CourseProgressResponse;
import sweetgirl.croissant.course.entity.Course;
import sweetgirl.croissant.course.entity.CourseImageFile;
import sweetgirl.croissant.course.entity.CourseProgress;
import sweetgirl.croissant.course.entity.CourseStore;
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
		Map<Long, List<CourseStore>> courseStoresByCourseId = courseStoreRepository
			.findAllByCourseIdInAndDeletedAtIsNullOrderByVisitOrderAsc(courseIds)
			.stream()
			.collect(Collectors.groupingBy(courseStore -> courseStore.getCourse().getId()));
		Map<Long, List<String>> storeThumbnailsByCourseId = courseStoreThumbnailReader
			.readByCourse(courseStoresByCourseId);

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

	public void refreshForMember(Long memberId) {
		refresh(memberId);
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

	public void refreshForCourse(Long courseId) {
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
				completedAt);
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
				completedAt);
		});
		return progresses;
	}

	private void updateCompletion(
			CourseProgress progress,
			long storeCount,
			long visitedStoreCount,
			LocalDateTime completedAt) {
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

	private Course getAccessibleCourse(Long courseId, Long memberId) {
		Course course = courseRepository.findByIdAndDeletedAtIsNull(courseId)
			.orElseThrow(() -> new CourseException(CourseErrorCode.COURSE_NOT_FOUND));
		boolean isOwner = course.getMember() != null && course.getMember().getId().equals(memberId);
		if (!course.isPublic() && !isOwner) {
			throw new CourseException(CourseErrorCode.COURSE_FORBIDDEN);
		}
		return course;
	}
}
