package sweetgirl.croissant.course.dto.response;

import java.util.List;

import sweetgirl.croissant.course.entity.Course;
import sweetgirl.croissant.course.entity.CourseImageFile;
import sweetgirl.croissant.course.enums.CourseType;

public record CourseDetailResponse(
		Long id,
		String title,
		String imageKey,
		String previewImageKey,
		int storeCount,
		List<String> regions,
		CourseType courseType,
		String authorName,
		Long authorId,
		String badge,
		boolean isPublic,
		boolean isMine,
		boolean isSaved,
		long savedCount,
		List<CourseStoreResponse> courseStores) {

	public static CourseDetailResponse of(
			Course course,
			CourseImageFile coverImage,
			boolean isMine,
			boolean isSaved,
			long savedCount,
			List<String> regions,
			List<CourseStoreResponse> courseStores) {
		String authorName = course.getMember() != null ? course.getMember().getNickname() : null;
		Long authorId = course.getMember() != null ? course.getMember().getId() : null;
		return new CourseDetailResponse(
			course.getId(),
			course.getTitle(),
			coverImage != null ? coverImage.getAwsKey() : null,
			coverImage != null ? coverImage.getResizedKey640() : null,
			courseStores.size(),
			regions,
			course.getType(),
			authorName,
			authorId,
			course.getBadge(),
			course.isPublic(),
			isMine,
			isSaved,
			savedCount,
			courseStores);
	}
}
