package sweetgirl.croissant.course.dto.response;

import java.util.List;

import sweetgirl.croissant.course.entity.Course;
import sweetgirl.croissant.course.enums.CourseType;

public record CourseSummaryResponse(
		Long id,
		String title,
		String coverImageKey,
		int storeCount,
		List<String> regions,
		CourseType courseType,
		String authorName,
		String badge) {

	public static CourseSummaryResponse of(
			Course course,
			String coverImageKey,
			int storeCount,
			List<String> regions) {
		String authorName = course.getMember() != null ? course.getMember().getNickname() : null;
		return new CourseSummaryResponse(
			course.getId(),
			course.getTitle(),
			coverImageKey,
			storeCount,
			regions,
			course.getType(),
			authorName,
			course.getBadge());
	}
}
