package sweetgirl.croissant.breadlog.dto.response;

import java.time.LocalDateTime;
import java.util.List;

import sweetgirl.croissant.breadlog.entity.BreadRecord;
import sweetgirl.croissant.breadlog.enums.RecordType;

public record BreadRecordResponse(
		Long id,
		Long storeId,
		String storeName,
		String regionKey,
		String memo,
		String lovedBread,
		List<String> lovedBreads,
		String towerBread,
		LocalDateTime eatenAt,
		boolean isPublic,
		LocalDateTime createdAt,
		LocalDateTime updatedAt,
		List<BreadRecordPhotoResponse> photos,
		List<BreadRecordTagResponse> tags,
		RecordType recordType,
		/** 고른 가게의 주소. 가게 없이 이름만 적은 기록이면 null */
		String storeAddress,
		/** "이 집 또 갈래요". 안 보낸 기록이면 null — 앱이 또 먹을래요로 추정해 보여 준다 */
		Boolean revisit,
		/** 10/22 추가 — 타임라인 줄의 ♡ · 💬, 신고로 숨겨졌으면 UNDER_REVIEW */
		long likeCount,
		long commentCount,
		String hiddenStatus) {

	/**
	 * @param lovedBreads 또 먹을래요(again) on 인 태그 이름 — 옛 하트 칩의 자리. 1.5.x 앱이 아직 읽는다
	 * @param towerBread 빵탑에 쌓인 빵. 사용자가 고르지 않은 옛 기록은 서버가 골라 채운 값이다
	 *                   ({@code TowerBreadPicker}). DB 의 빈 칸과 구분하지 않고 늘 값이 실린다.
	 */
	public static BreadRecordResponse of(
			BreadRecord record,
			List<BreadRecordPhotoResponse> photos,
			List<BreadRecordTagResponse> tags,
			String towerBread) {
		return of(record, photos, tags, towerBread, 0, 0);
	}

	public static BreadRecordResponse of(
			BreadRecord record,
			List<BreadRecordPhotoResponse> photos,
			List<BreadRecordTagResponse> tags,
			String towerBread,
			long likeCount,
			long commentCount) {
		List<String> lovedBreads = lovedBreadsOf(tags);
		return new BreadRecordResponse(
			record.getId(),
			record.getStore() != null ? record.getStore().getId() : null,
			record.getStoreName(),
			record.getRegionKey(),
			record.getMemo(),
			lovedBreads.isEmpty() ? null : lovedBreads.get(0),
			lovedBreads,
			towerBread,
			record.getEatenAt(),
			record.isPublic(),
			record.getCreatedAt(),
			record.getUpdatedAt(),
			photos,
			tags,
			record.getRecordType(),
			record.getStore() != null ? record.getStore().getAddress() : null,
			record.getRevisit(),
			likeCount,
			commentCount,
			record.isHidden() ? MemberBreadRecordResponse.UNDER_REVIEW : null);
	}

	static List<String> lovedBreadsOf(List<BreadRecordTagResponse> tags) {
		return tags.stream()
			.filter(BreadRecordTagResponse::wantsAgain)
			.map(BreadRecordTagResponse::name)
			.distinct()
			.toList();
	}
}
