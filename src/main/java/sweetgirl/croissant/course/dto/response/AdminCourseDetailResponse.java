package sweetgirl.croissant.course.dto.response;

import java.time.LocalDateTime;
import java.util.List;

import sweetgirl.croissant.course.entity.Course;

public record AdminCourseDetailResponse(
		Long id,
		String title,
		String badge,
		String coverImageKey,
		Integer pinOrder,
		int storeCount,
		long savedCount,
		LocalDateTime createdAt,
		List<CourseStoreResponse> courseStores) {

	public static AdminCourseDetailResponse of(
			Course course,
			String coverImageKey,
			long savedCount,
			List<CourseStoreResponse> courseStores) {
		return new AdminCourseDetailResponse(
			course.getId(),
			course.getTitle(),
			course.getBadge(),
			coverImageKey,
			course.getPinOrder(),
			courseStores.size(),
			savedCount,
			course.getCreatedAt(),
			courseStores);
	}
}
