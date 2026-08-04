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
import sweetgirl.croissant.course.dto.response.CourseProgressResponse;
import sweetgirl.croissant.course.entity.Course;
import sweetgirl.croissant.course.entity.CourseImageFile;
import sweetgirl.croissant.course.entity.CourseProgress;
import sweetgirl.croissant.course.exception.CourseException;
import sweetgirl.croissant.course.repository.CourseImageFileRepository;
import sweetgirl.croissant.course.repository.CourseProgressRepository;
import sweetgirl.croissant.course.repository.CourseRepository;
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
		if (progresses.isEmpty()) {
			return List.of();
		}

		Set<Long> ongoingCourseIds = progresses.stream()
			.filter(progress -> progress.getStoppedAt() == null && progress.getCompletedAt() == null)
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

		return progresses.stream()
			.filter(progress -> ongoingCourseIds.contains(progress.getCourse().getId()))
			.map(progress -> toResponse(
				progress,
				imageByCourseId.get(progress.getCourse().getId()),
				aggregateByCourseId.get(progress.getCourse().getId())))
			.sorted(Comparator.comparing(this::lastActivityAt).reversed())
			.toList();
	}

	public void refreshForMember(Long memberId) {
		refresh(memberId);
	}

	private List<CourseProgress> refresh(Long memberId) {
		List<CourseProgress> progresses = courseProgressRepository.findAllByMemberId(memberId);
		if (progresses.isEmpty()) {
			return progresses;
		}
		List<Long> courseIds = progresses.stream().map(progress -> progress.getCourse().getId()).toList();
		Map<Long, CourseProgressAggregateProjection> aggregateByCourseId = findAggregates(memberId, courseIds);
		LocalDateTime completedAt = LocalDateTime.now();
		progresses.forEach(progress -> {
			CourseProgressAggregateProjection aggregate = aggregateByCourseId.get(progress.getCourse().getId());
			boolean completed = aggregate != null
				&& aggregate.getStoreCount() > 0
				&& aggregate.getVisitedStoreCount() >= aggregate.getStoreCount();
			if (completed) {
				progress.markCompleted(completedAt);
			} else {
				progress.markIncomplete();
			}
		});
		return progresses;
	}

	private Map<Long, CourseProgressAggregateProjection> findAggregates(Long memberId, List<Long> courseIds) {
		return courseProgressRepository.findAggregates(memberId, courseIds).stream()
			.collect(Collectors.toMap(CourseProgressAggregateProjection::getCourseId, Function.identity()));
	}

	private CourseProgressResponse toResponse(
			CourseProgress progress,
			CourseImageFile image,
			CourseProgressAggregateProjection aggregate) {
		int storeCount = aggregate == null ? 0 : Math.toIntExact(aggregate.getStoreCount());
		int visitedStoreCount = aggregate == null ? 0 : Math.toIntExact(aggregate.getVisitedStoreCount());
		return CourseProgressResponse.of(
			progress,
			image,
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
