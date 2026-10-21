package sweetgirl.croissant.course.dto.response;

import java.time.LocalDateTime;

import sweetgirl.croissant.course.entity.Course;
import sweetgirl.croissant.course.entity.CourseImageFile;

public record AdminCourseResponse(
		Long id,
		String title,
		String badge,
		String categoryGroup,
		String category,
		String imageKey,
		String previewImageKey,
		Integer pinOrder,
		int storeCount,
		long savedCount,
		LocalDateTime createdAt) {

	public static AdminCourseResponse of(
			Course course,
			CourseImageFile coverImage,
			int storeCount,
			long savedCount) {
		return new AdminCourseResponse(
			course.getId(),
			course.getTitle(),
			course.getBadge(),
			course.getCategoryGroup(),
			course.getCategory(),
			coverImage != null ? coverImage.getAwsKey() : null,
			coverImage != null ? coverImage.getResizedKey640() : null,
			course.getPinOrder(),
			storeCount,
			savedCount,
			course.getCreatedAt());
	}
}
