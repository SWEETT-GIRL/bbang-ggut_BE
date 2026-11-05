package sweetgirl.croissant.course.dto.response;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import sweetgirl.croissant.course.entity.Course;
import sweetgirl.croissant.course.entity.CourseImageFile;
import sweetgirl.croissant.course.enums.CourseType;
import sweetgirl.croissant.course.enums.CourseVisibility;

public record CourseDetailResponse(
		Long id,
		String title,
		String imageKey,
		String previewImageKey,
		/** 대표사진이 없을 때 앱이 콜라주로 그릴 가게 썸네일 — 사진 있는 가게만, 방문 순서, 최대 4장 */
		List<String> storeThumbnailKeys,
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
		long finisherCount,
		LocalDateTime createdAt,
		String region,
		LocalDate startDate,
		LocalDate endDate,
		List<CourseStoreResponse> courseStores,
		// ── 동시편집 ──
		/** = isMine. 이름을 하나 더 두는 이유: 앱에서 "방장 전용"과 "편집 가능"을 갈라 읽게 */
		boolean isOwner,
		/** 방장 또는 활성 멤버. 큐레이션은 항상 false */
		boolean canEdit,
		/** 방장 아닌 활성 멤버 */
		boolean isMember,
		/** 방장 포함 활성 멤버 수. 협업 아니면 0 (방장 행도 없다) */
		int memberCount,
		/** 참여 순서. 협업 아니면 [] */
		List<CourseMemberResponse> members,
		/** 내 course_members 행의 이름. 없으면 null */
		String myDisplayName,
		int dayCount,
		CourseVisibility visibility,
		/** 링크 공개 공유 토큰. 방장에게만, UNLISTED 일 때만. 그 외엔 null */
		String shareToken,
		/** 폴링 ETag 와 같은 값. 앱이 If-None-Match 에 되돌려 보낸다 */
		long version,
		/** 메모 초안 자동저장(PUT memo-draft · STOMP) 을 쓸 수 있는가. 서버 스위치 — false 면 앱은 PATCH 만 쓴다 */
		boolean draftEnabled) {

	public static CourseDetailResponse of(
			Course course,
			CourseImageFile coverImage,
			List<String> storeThumbnailKeys,
			boolean isMine,
			boolean isSaved,
			long savedCount,
			long finisherCount,
			List<String> regions,
			List<CourseStoreResponse> courseStores,
			List<CourseMemberResponse> members,
			boolean isMember,
			String myDisplayName,
			String shareToken,
			boolean draftEnabled) {
		String authorName = course.getMember() != null ? course.getMember().getNickname() : null;
		Long authorId = course.getMember() != null ? course.getMember().getId() : null;
		boolean canEdit = course.getType() != CourseType.CURATED && (isMine || isMember);
		return new CourseDetailResponse(
			course.getId(),
			course.getTitle(),
			coverImage != null ? coverImage.getAwsKey() : null,
			coverImage != null ? coverImage.getResizedKey640() : null,
			storeThumbnailKeys,
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
			finisherCount,
			course.getCreatedAt(),
			course.getRegion(),
			course.getStartDate(),
			course.getEndDate(),
			courseStores,
			isMine,
			canEdit,
			isMember,
			members.size(),
			members,
			myDisplayName,
			course.getDayCount(),
			course.getVisibility(),
			shareToken,
			course.getVersion(),
			draftEnabled);
	}
}
