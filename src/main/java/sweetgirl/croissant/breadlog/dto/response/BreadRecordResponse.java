package sweetgirl.croissant.breadlog.dto.response;

import java.time.LocalDateTime;
import java.util.List;

import sweetgirl.croissant.breadlog.entity.BreadRecord;

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
		List<BreadRecordTagResponse> tags) {

	/**
	 * @param towerBread 빵탑에 쌓인 빵. 사용자가 고르지 않은 옛 기록은 서버가 골라 채운 값이다
	 *                   ({@code TowerBreadPicker}). DB 의 빈 칸과 구분하지 않고 늘 값이 실린다.
	 */
	public static BreadRecordResponse of(
			BreadRecord record,
			List<BreadRecordPhotoResponse> photos,
			List<BreadRecordTagResponse> tags,
			String towerBread) {
		return new BreadRecordResponse(
			record.getId(),
			record.getStore() != null ? record.getStore().getId() : null,
			record.getStoreName(),
			record.getRegionKey(),
			record.getMemo(),
			record.getLovedBread(),
			List.copyOf(record.getLovedBreads()),
			towerBread,
			record.getEatenAt(),
			record.isPublic(),
			record.getCreatedAt(),
			record.getUpdatedAt(),
			photos,
			tags);
	}
}
