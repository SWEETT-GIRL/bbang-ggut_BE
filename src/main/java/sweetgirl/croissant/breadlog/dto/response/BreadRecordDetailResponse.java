package sweetgirl.croissant.breadlog.dto.response;

import java.time.LocalDateTime;
import java.util.List;

import sweetgirl.croissant.breadlog.entity.BreadRecord;
import sweetgirl.croissant.comment.dto.response.CommentResponse;
import sweetgirl.croissant.profile.dto.response.AuthorCardResponse;

/**
 * 빵로그 상세(남의 것 · 내 것). 같이 간 사람은 P6 에서 채운다 — 자리만 먼저 둔다(빈 배열).
 */
public record BreadRecordDetailResponse(
		Long id,
		AuthorCardResponse author,
		Long storeId,
		String storeName,
		String storeAddress,
		String regionKey,
		String memo,
		List<String> lovedBreads,
		String towerBread,
		LocalDateTime eatenAt,
		Boolean revisit,
		boolean isPublic,
		List<BreadRecordPhotoResponse> photos,
		List<BreadRecordTagResponse> tags,
		long likeCount,
		boolean likedByMe,
		long commentCount,
		CommentResponse firstComment,
		List<Object> companions,
		String hiddenStatus) {

	public static BreadRecordDetailResponse of(BreadRecord record, AuthorCardResponse author,
			List<BreadRecordPhotoResponse> photos, List<BreadRecordTagResponse> tags, String towerBread,
			long likeCount, boolean likedByMe, long commentCount, CommentResponse firstComment) {
		return new BreadRecordDetailResponse(
			record.getId(),
			author,
			record.getStore() != null ? record.getStore().getId() : null,
			record.getStoreName(),
			record.getStore() != null ? record.getStore().getAddress() : null,
			record.getRegionKey(),
			record.getMemo(),
			BreadRecordResponse.lovedBreadsOf(tags),
			towerBread,
			record.getEatenAt(),
			record.getRevisit(),
			record.isPublic(),
			photos,
			tags,
			likeCount,
			likedByMe,
			commentCount,
			firstComment,
			List.of(),
			record.isHidden() ? MemberBreadRecordResponse.UNDER_REVIEW : null);
	}
}
