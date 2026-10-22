package sweetgirl.croissant.breadlog.dto.response;

import java.time.LocalDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import sweetgirl.croissant.breadlog.entity.BreadRecord;
import sweetgirl.croissant.breadlog.entity.BreadRecordTag;

/**
 * [기록 가져오기]의 초안 — 빵로그 쓰기 화면을 채우는 재료. 앱은 이걸로 화면을 채운 뒤 평소처럼 {@code POST /bread-records} 로
 * 저장한다(보상도 평소와 같다). 사진은 내 S3 경로로 복사된 새 키라 원본이 지워져도 남는다. 메모 · 평가는 안 가져온다.
 */
public record BreadRecordImportDraftResponse(
		Long sourceRecordId,
		Long storeId,
		String storeName,
		String regionKey,
		LocalDateTime eatenAt,
		List<String> lovedBreads,
		String towerBread,
		/** 그대로 {@code photos} 요청에 실을 수 있는 모양 */
		List<Photo> photos,
		/** 그대로 {@code tags} 요청에 실을 수 있는 모양 */
		List<Tag> tags) {

	public static BreadRecordImportDraftResponse of(BreadRecord record, List<Photo> photos, List<Tag> tags) {
		return new BreadRecordImportDraftResponse(
			record.getId(),
			record.getStore() != null ? record.getStore().getId() : null,
			record.getStoreName(),
			record.getRegionKey(),
			record.getEatenAt(),
			record.getLovedBreads(),
			record.getTowerBread(),
			photos,
			tags);
	}

	public record Photo(
			String fileName,
			String contentType,
			String awsKey,
			int sortOrder,
			Integer width,
			Integer height) {
	}

	public record Tag(
			Integer photoSortOrder,
			String name,
			Long menuId,
			@JsonProperty("x") Double positionX,
			@JsonProperty("y") Double positionY,
			String style) {
		public static Tag from(BreadRecordTag tag) {
			return new Tag(
				tag.getPhoto() == null ? null : tag.getPhoto().getSortOrder(),
				tag.getName(),
				tag.getMenu() == null ? null : tag.getMenu().getId(),
				tag.getPositionX(),
				tag.getPositionY(),
				tag.getStyle());
		}
	}
}
