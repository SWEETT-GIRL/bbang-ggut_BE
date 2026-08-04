package sweetgirl.croissant.course.dto.response;

import java.time.LocalDateTime;

import sweetgirl.croissant.course.entity.CourseImageFile;
import sweetgirl.croissant.course.entity.CourseProgress;

public record CourseProgressResponse(
		Long courseId,
		String title,
		String imageKey,
		String thumbnailImageKey,
		int storeCount,
		int visitedStoreCount,
		int progressPercent,
		LocalDateTime startedAt,
		LocalDateTime lastTouchedAt) {

	public static CourseProgressResponse of(
			CourseProgress progress,
			CourseImageFile coverImage,
			int storeCount,
			int visitedStoreCount,
			LocalDateTime lastTouchedAt) {
		int progressPercent = storeCount == 0 ? 0 : visitedStoreCount * 100 / storeCount;
		return new CourseProgressResponse(
			progress.getCourse().getId(),
			progress.getCourse().getTitle(),
			coverImage != null ? coverImage.getAwsKey() : null,
			coverImage != null ? coverImage.getResizedKey320() : null,
			storeCount,
			visitedStoreCount,
			progressPercent,
			progress.getStartedAt(),
			lastTouchedAt);
	}
}
