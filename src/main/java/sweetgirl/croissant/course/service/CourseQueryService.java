package sweetgirl.croissant.course.service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
import sweetgirl.croissant.store.RegionFormatter;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CourseQueryService {

	private final CourseRepository courseRepository;
	private final CourseStoreRepository courseStoreRepository;
	private final SavedCourseRepository savedCourseRepository;
	private final CourseImageFileRepository courseImageFileRepository;

	public List<CourseSummaryResponse> getCurationCourses() {
		List<Course> courses = courseRepository.findAllByIsPublicTrueAndDeletedAtIsNull();
		Map<Long, Long> savedCountByCourseId = findSavedCountByCourseId(courses);
		List<Course> sortedCourses = courses.stream()
			.sorted(Comparator
				.<Course>comparingInt(course -> course.getPinOrder() != null ? course.getPinOrder() : Integer.MAX_VALUE)
				.thenComparingLong(course -> -savedCountByCourseId.getOrDefault(course.getId(), 0L)))
			.toList();
		return buildSummaryResponses(sortedCourses);
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

		if (!course.isPublic()) {
			if (memberId == null || course.getMember() == null || !course.getMember().getId().equals(memberId)) {
				throw new CourseException(CourseErrorCode.COURSE_FORBIDDEN);
			}
		}

		List<CourseStore> courseStores = courseStoreRepository
			.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(courseId);
		List<CourseStoreResponse> courseStoreResponses = courseStores.stream()
			.map(CourseStoreResponse::from)
			.toList();

		CourseImageFile coverImage = courseImageFileRepository.findByCourseId(courseId).orElse(null);

		boolean isMine = memberId != null && course.getMember() != null
			&& course.getMember().getId().equals(memberId);
		boolean isSaved = memberId != null && savedCourseRepository.existsByMemberIdAndCourseId(memberId, courseId);
		long savedCount = savedCourseRepository.countByCourseId(courseId);

		List<String> regions = regionsOf(courseStores);

		return CourseDetailResponse.of(course, coverImage, isMine, isSaved, savedCount, regions, courseStoreResponses);
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

		return courses.stream()
			.map(course -> {
				List<CourseStore> courseStores = courseStoresByCourseId.getOrDefault(course.getId(), List.of());
				List<String> regions = regionsOf(courseStores);
				return CourseSummaryResponse.of(
					course,
					coverImageByCourseId.get(course.getId()),
					courseStores.size(),
					regions,
					savedCountByCourseId.getOrDefault(course.getId(), 0L));
			})
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
