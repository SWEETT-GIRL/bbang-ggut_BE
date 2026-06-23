package sweetgirl.croissant.course.dto.response;

import java.util.List;

import sweetgirl.croissant.course.entity.Course;
import sweetgirl.croissant.course.enums.CourseType;

public record CourseDetailResponse(
		Long id,
		String title,
		String coverImageKey,
		int storeCount,
		List<String> regions,
		CourseType courseType,
		String authorName,
		String badge,
		boolean isSaved,
		long savedCount,
		List<CourseStoreResponse> courseStores) {

	public static CourseDetailResponse of(
			Course course,
			String coverImageKey,
			boolean isSaved,
			long savedCount,
			List<CourseStoreResponse> courseStores) {
		List<String> regions = courseStores.stream()
			.map(CourseStoreResponse::region)
			.filter(region -> region != null)
			.distinct()
			.toList();
		String authorName = course.getMember() != null ? course.getMember().getNickname() : null;
		return new CourseDetailResponse(
			course.getId(),
			course.getTitle(),
			coverImageKey,
			courseStores.size(),
			regions,
			course.getType(),
			authorName,
			course.getBadge(),
			isSaved,
			savedCount,
			courseStores);
	}
}
