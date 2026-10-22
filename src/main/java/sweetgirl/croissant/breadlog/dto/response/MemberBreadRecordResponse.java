package sweetgirl.croissant.breadlog.dto.response;

import java.time.LocalDateTime;
import java.util.List;

import sweetgirl.croissant.breadlog.entity.BreadRecord;

/**
 * 프로필의 공개 빵로그 한 줄. 필드는 더하기만 한다.
 * {@code hiddenStatus} 는 신고로 숨겨진 내 기록을 나한테만 보여 줄 때 "UNDER_REVIEW", 아니면 null.
 */
public record MemberBreadRecordResponse(
		Long id,
		Long storeId,
		String storeName,
		String regionKey,
		String memo,
		List<String> lovedBreads,
		LocalDateTime eatenAt,
		boolean isPublic,
		List<BreadRecordPhotoResponse> photos,
		List<BreadRecordTagResponse> tags,
		long likeCount,
		long commentCount,
		boolean likedByMe,
		String hiddenStatus) {

	public static final String UNDER_REVIEW = "UNDER_REVIEW";

	public static MemberBreadRecordResponse of(BreadRecord record, List<BreadRecordPhotoResponse> photos,
			List<BreadRecordTagResponse> tags, long likeCount, long commentCount, boolean likedByMe) {
		return new MemberBreadRecordResponse(
			record.getId(),
			record.getStore() != null ? record.getStore().getId() : null,
			record.getStoreName(),
			record.getRegionKey(),
			record.getMemo(),
			BreadRecordResponse.lovedBreadsOf(tags),
			record.getEatenAt(),
			record.isPublic(),
			photos,
			tags,
			likeCount,
			commentCount,
			likedByMe,
			record.isHidden() ? UNDER_REVIEW : null);
	}
}
