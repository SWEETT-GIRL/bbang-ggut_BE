package sweetgirl.croissant.breadlog.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

import sweetgirl.croissant.breadlog.entity.BreadRecordTag;

public record BreadRecordTagResponse(
		Long id,
		Long photoId,
		int photoSortOrder,
		String name,
		/** 룰 판정이 먼저고, 룰이 '빵'·'기타'로 물러섰을 때만 가리키는 메뉴의 분류가 대신한다 */
		String breadCategory,
		/** 이 태그가 가리키는 가게 메뉴. 가게를 안 고른 기록이나 그 가게에 없는 이름이면 null */
		Long menuId,
		@JsonProperty("x") double positionX,
		@JsonProperty("y") double positionY,
		String style) {

	public static BreadRecordTagResponse from(BreadRecordTag tag) {
		return new BreadRecordTagResponse(
			tag.getId(),
			tag.getPhoto().getId(),
			tag.getPhoto().getSortOrder(),
			tag.getName(),
			tag.resolvedCategory(),
			tag.getMenu() == null ? null : tag.getMenu().getId(),
			tag.getPositionX(),
			tag.getPositionY(),
			tag.getStyle());
	}
}
