package sweetgirl.croissant.course.dto.response;

import java.time.LocalDate;
import java.util.List;

import sweetgirl.croissant.course.entity.Course;
import sweetgirl.croissant.course.entity.CourseImageFile;
import sweetgirl.croissant.course.enums.CourseType;

public record CourseSummaryResponse(
		Long id,
		String title,
		String imageKey,
		String thumbnailImageKey,
		int storeCount,
		List<Long> storeIds,
		List<String> regions,
		CourseType courseType,
		String authorName,
		String badge,
		long savedCount,
		Integer pinOrder,
		long finisherCount,
		String region,
		LocalDate startDate,
		LocalDate endDate) {

	public static CourseSummaryResponse of(
			Course course,
			CourseImageFile coverImage,
			int storeCount,
			List<Long> storeIds,
			List<String> regions,
			long savedCount,
			long finisherCount) {
		String authorName = course.getMember() != null ? course.getMember().getNickname() : null;
		return new CourseSummaryResponse(
			course.getId(),
			course.getTitle(),
			coverImage != null ? coverImage.getAwsKey() : null,
			coverImage != null ? coverImage.getResizedKey320() : null,
			storeCount,
			storeIds,
			regions,
			course.getType(),
			authorName,
			course.getBadge(),
			savedCount,
			course.getPinOrder(),
			finisherCount,
			course.getRegion(),
			course.getStartDate(),
			course.getEndDate());
	}
}
