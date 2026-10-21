package sweetgirl.croissant.breadlog.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

import sweetgirl.croissant.breadlog.entity.BreadRecordTag;

public record BreadRecordTagResponse(
		Long id,
		/** 사진에 안 붙은 빵 이름이면 photoId · photoSortOrder · x · y 가 모두 null 이다 */
		Long photoId,
		Integer photoSortOrder,
		String name,
		/** 룰 판정이 먼저고, 룰이 '빵'·'기타'로 물러섰을 때만 가리키는 메뉴의 분류가 대신한다 */
		String breadCategory,
		/** 이 태그가 가리키는 가게 메뉴. 가게를 안 고른 기록이나 그 가게에 없는 이름이면 null */
		Long menuId,
		@JsonProperty("x") Double positionX,
		@JsonProperty("y") Double positionY,
		String style,
		/** 빵 평가. 아무것도 안 남긴 태그는 null */
		BreadTagRatingResponse rating) {

	public static BreadRecordTagResponse from(BreadRecordTag tag) {
		return new BreadRecordTagResponse(
			tag.getId(),
			tag.getPhoto() == null ? null : tag.getPhoto().getId(),
			tag.getPhoto() == null ? null : tag.getPhoto().getSortOrder(),
			tag.getName(),
			tag.resolvedCategory(),
			tag.getMenu() == null ? null : tag.getMenu().getId(),
			tag.getPositionX(),
			tag.getPositionY(),
			tag.getStyle(),
			tag.rating().map(BreadTagRatingResponse::from).orElse(null));
	}

	public boolean wantsAgain() {
		return rating != null && Boolean.TRUE.equals(rating.again());
	}
}
