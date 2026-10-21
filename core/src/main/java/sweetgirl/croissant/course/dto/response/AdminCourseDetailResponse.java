package sweetgirl.croissant.course.dto.response;

import java.time.LocalDateTime;
import java.util.List;

import sweetgirl.croissant.course.entity.Course;
import sweetgirl.croissant.course.entity.CourseImageFile;

public record AdminCourseDetailResponse(
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
		LocalDateTime createdAt,
		List<CourseStoreResponse> courseStores) {

	public static AdminCourseDetailResponse of(
			Course course,
			CourseImageFile coverImage,
			long savedCount,
			List<CourseStoreResponse> courseStores) {
		return new AdminCourseDetailResponse(
			course.getId(),
			course.getTitle(),
			course.getBadge(),
			course.getCategoryGroup(),
			course.getCategory(),
			coverImage != null ? coverImage.getAwsKey() : null,
			coverImage != null ? coverImage.getResizedKey640() : null,
			course.getPinOrder(),
			courseStores.size(),
			savedCount,
			course.getCreatedAt(),
			courseStores);
	}
}
