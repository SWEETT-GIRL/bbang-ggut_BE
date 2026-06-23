package sweetgirl.croissant.admin.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.course.code.CourseErrorCode;
import sweetgirl.croissant.course.dto.response.AdminCourseDetailResponse;
import sweetgirl.croissant.course.dto.response.AdminCourseResponse;
import sweetgirl.croissant.course.dto.response.CourseStoreResponse;
import sweetgirl.croissant.course.entity.Course;
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
public class AdminCourseQueryService {

	private final CourseRepository courseRepository;
	private final CourseStoreRepository courseStoreRepository;
	private final CourseImageFileRepository courseImageFileRepository;
	private final SavedCourseRepository savedCourseRepository;

	public List<AdminCourseResponse> getCuratedCourses() {
		return courseRepository.findAllByTypeAndDeletedAtIsNullOrderByCreatedAtDesc(CourseType.CURATED).stream()
			.map(this::buildAdminCourseResponse)
			.toList();
	}

	public AdminCourseDetailResponse getCuratedCourseDetail(Long courseId) {
		Course course = courseRepository.findByIdAndDeletedAtIsNull(courseId)
			.orElseThrow(() -> new CourseException(CourseErrorCode.COURSE_NOT_FOUND));

		List<CourseStore> courseStores = courseStoreRepository
			.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(courseId);
		List<CourseStoreResponse> courseStoreResponses = courseStores.stream()
			.map(CourseStoreResponse::from)
			.toList();

		String coverImageKey = courseImageFileRepository.findByCourseId(courseId)
			.map(imageFile -> imageFile.getAwsKey())
			.orElse(null);
		long savedCount = savedCourseRepository.countByCourseId(courseId);

		return AdminCourseDetailResponse.of(course, coverImageKey, savedCount, courseStoreResponses);
	}

	private AdminCourseResponse buildAdminCourseResponse(Course course) {
		int storeCount = courseStoreRepository
			.findAllByCourseIdAndDeletedAtIsNullOrderByVisitOrderAsc(course.getId()).size();
		String coverImageKey = courseImageFileRepository.findByCourseId(course.getId())
			.map(imageFile -> imageFile.getAwsKey())
			.orElse(null);
		long savedCount = savedCourseRepository.countByCourseId(course.getId());
		return AdminCourseResponse.of(course, coverImageKey, storeCount, savedCount);
	}
}
