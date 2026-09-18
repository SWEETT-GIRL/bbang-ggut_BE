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
		LocalDateTime eatenAt,
		boolean isPublic,
		LocalDateTime createdAt,
		LocalDateTime updatedAt,
		List<BreadRecordPhotoResponse> photos,
		List<BreadRecordTagResponse> tags) {

	public static BreadRecordResponse of(
			BreadRecord record,
			List<BreadRecordPhotoResponse> photos,
			List<BreadRecordTagResponse> tags) {
		return new BreadRecordResponse(
			record.getId(),
			record.getStore() != null ? record.getStore().getId() : null,
			record.getStoreName(),
			record.getRegionKey(),
			record.getMemo(),
			record.getLovedBread(),
			List.copyOf(record.getLovedBreads()),
			record.getEatenAt(),
			record.isPublic(),
			record.getCreatedAt(),
			record.getUpdatedAt(),
			photos,
			tags);
	}
}
