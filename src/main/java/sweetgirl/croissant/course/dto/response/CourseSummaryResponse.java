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
		/** 대표사진이 없을 때 앱이 콜라주로 그릴 가게 썸네일 — 사진 있는 가게만, 방문 순서, 최대 4장 */
		List<String> storeThumbnailKeys,
		/** 앞에서부터 가게 이름 몇 개(방문 순서) — 목록에서 "아는 빵집이 있나"를 보여주는 데 쓴다 */
		List<String> storeNames,
		int storeCount,
		List<Long> storeIds,
		List<String> regions,
		CourseType courseType,
		String authorName,
		String badge,
		/** 어드민이 지정한 분류 — 탐색 탭 필터 축 이름("대학가")과 그 안의 값("홍대"). 분류 없는 코스는 둘 다 null */
		String categoryGroup,
		String category,
		long savedCount,
		Integer pinOrder,
		long finisherCount,
		String region,
		LocalDate startDate,
		LocalDate endDate,
		/** 방장 아닌 활성 멤버 — 내 빵투어의 '함께' 배지. 내 목록 말고는 항상 false */
		boolean isMember) {

	public static CourseSummaryResponse of(
			Course course,
			CourseImageFile coverImage,
			List<String> storeThumbnailKeys,
			List<String> storeNames,
			int storeCount,
			List<Long> storeIds,
			List<String> regions,
			long savedCount,
			long finisherCount,
			boolean isMember) {
		String authorName = course.getMember() != null ? course.getMember().getNickname() : null;
		return new CourseSummaryResponse(
			course.getId(),
			course.getTitle(),
			coverImage != null ? coverImage.getAwsKey() : null,
			coverImage != null ? coverImage.getResizedKey320() : null,
			storeThumbnailKeys,
			storeNames,
			storeCount,
			storeIds,
			regions,
			course.getType(),
			authorName,
			course.getBadge(),
			course.getCategoryGroup(),
			course.getCategory(),
			savedCount,
			course.getPinOrder(),
			finisherCount,
			course.getRegion(),
			course.getStartDate(),
			course.getEndDate(),
			isMember);
	}
}
