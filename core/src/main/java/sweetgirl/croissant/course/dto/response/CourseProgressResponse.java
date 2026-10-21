package sweetgirl.croissant.course.dto.response;

import java.time.LocalDateTime;
import java.util.List;

import sweetgirl.croissant.course.entity.CourseImageFile;
import sweetgirl.croissant.course.entity.CourseProgress;
import sweetgirl.croissant.course.enums.CourseProgressStatus;

public record CourseProgressResponse(
		Long id,
		String title,
		String imageKey,
		String thumbnailImageKey,
		/** 대표사진이 없을 때 앱이 콜라주로 그릴 가게 썸네일 — 사진 있는 가게만, 방문 순서, 최대 4장 */
		List<String> storeThumbnailKeys,
		int storeCount,
		int visitedStoreCount,
		int progressPercent,
		LocalDateTime startedAt,
		LocalDateTime lastTouchedAt,
		CourseProgressStatus status,
		LocalDateTime finishedAt) {

	public static CourseProgressResponse of(
			CourseProgress progress,
			CourseImageFile coverImage,
			List<String> storeThumbnailKeys,
			int storeCount,
			int visitedStoreCount,
			LocalDateTime lastTouchedAt) {
		int progressPercent = storeCount == 0 ? 0 : visitedStoreCount * 100 / storeCount;
		return new CourseProgressResponse(
			progress.getCourse().getId(),
			progress.getCourse().getTitle(),
			coverImage != null ? coverImage.getAwsKey() : null,
			coverImage != null ? coverImage.getResizedKey320() : null,
			storeThumbnailKeys,
			storeCount,
			visitedStoreCount,
			progressPercent,
			progress.getStartedAt(),
			lastTouchedAt,
			progress.getStatus(),
			progress.getFinishedAt());
	}
}
