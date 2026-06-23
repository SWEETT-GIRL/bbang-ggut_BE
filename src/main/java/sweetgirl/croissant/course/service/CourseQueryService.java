package sweetgirl.croissant.course.service;

import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.course.code.CourseErrorCode;
import sweetgirl.croissant.course.dto.response.CourseDetailResponse;
import sweetgirl.croissant.course.dto.response.CourseStoreResponse;
import sweetgirl.croissant.course.dto.response.CourseSummaryResponse;
import sweetgirl.croissant.course.entity.Course;
import sweetgirl.croissant.course.entity.CourseImageFile;
import sweetgirl.croissant.course.entity.CourseStore;
import sweetgirl.croissant.course.enums.CourseType;
import sweetgirl.croissant.course.exception.CourseException;
import sweetgirl.croissant.course.repository.CourseImageFileRepository;
import sweetgirl.croissant.course.repository.CourseRepository;
import sweetgirl.croissant.course.repository.CourseStoreRepository;
import sweetgirl.croissant.course.repository.SavedCourseRepository;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CourseQueryService {

	private final CourseRepository courseRepository;
	private final CourseStoreRepository courseStoreRepository;
	private final SavedCourseRepository savedCourseRepository;
	private final CourseImageFileRepository courseImageFileRepository;

	public List<CourseSummaryResponse> getCurationCourses() {
		return courseRepository.findAllByIsPublicTrueAndDeletedAtIsNull().stream()
			.sorted(Comparator
				.<Course>comparingInt(c -> c.getPinOrder() != null ? c.getPinOrder() : Integer.MAX_VALUE)
				.thenComparingLong(c -> -savedCourseRepository.countByCourseId(c.getId())))
			.map(this::buildSummaryResponse)
			.toList();
	}

	public List<CourseSummaryResponse> getMyCourses(Long memberId) {
		return courseRepository
			.findAllByMemberIdAndTypeInAndDeletedAtIsNull(memberId, List.of(CourseType.CUSTOM, CourseType.FORKED))
			.stream()
			.map(this::buildSummaryResponse)
			.toList();
	}

	public List<CourseSummaryResponse> getSavedCourses(Long memberId) {
		return savedCourseRepository.findAllByMemberId(memberId).stream()
			.filter(savedCourse -> !savedCourse.getCourse().isDeleted())
			.map(savedCourse -> buildSummaryResponse(savedCourse.getCourse()))
			.toList();
	}

	public CourseDetailResponse getCourseDetail(Long courseId, Long memberId) {
		Course course = courseRepository.findByIdAndDeletedAtIsNull(courseId)
			.orElseThrow(() -> new CourseException(CourseErrorCode.COURSE_NOT_FOUND));

		if (!course.isPublic()) {
			if (memberId == null || !course.getMember().getId().equals(memberId)) {
				throw new CourseException(CourseErrorCode.COURSE_FORBIDDEN);
			}
		}

		List<CourseStore> courseStores = courseStoreRepository
			.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(courseId);
		List<CourseStoreResponse> courseStoreResponses = courseStores.stream()
			.map(CourseStoreResponse::from)
			.toList();

		CourseImageFile coverImage = courseImageFileRepository.findByCourseId(courseId).orElse(null);

		boolean isSaved = memberId != null && savedCourseRepository.existsByMemberIdAndCourseId(memberId, courseId);
		long savedCount = savedCourseRepository.countByCourseId(courseId);

		return CourseDetailResponse.of(course, coverImage, isSaved, savedCount, courseStoreResponses);
	}

	private CourseSummaryResponse buildSummaryResponse(Course course) {
		List<CourseStore> courseStores = courseStoreRepository
			.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(course.getId());

		CourseImageFile coverImage = courseImageFileRepository.findByCourseId(course.getId()).orElse(null);

		List<String> regions = courseStores.stream()
			.map(courseStore -> courseStore.getStore().getRegion2nd())
			.filter(region -> region != null)
			.distinct()
			.toList();

		return CourseSummaryResponse.of(course, coverImage, courseStores.size(), regions);
	}
}
