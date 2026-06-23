package sweetgirl.croissant.course.dto.response;

import java.time.LocalDateTime;

import sweetgirl.croissant.course.entity.Course;

public record AdminCourseResponse(
		Long id,
		String title,
		String badge,
		String coverImageKey,
		Integer pinOrder,
		int storeCount,
		long savedCount,
		LocalDateTime createdAt) {

	public static AdminCourseResponse of(
			Course course,
			String coverImageKey,
			int storeCount,
			long savedCount) {
		return new AdminCourseResponse(
			course.getId(),
			course.getTitle(),
			course.getBadge(),
			coverImageKey,
			course.getPinOrder(),
			storeCount,
			savedCount,
			course.getCreatedAt());
	}
}
