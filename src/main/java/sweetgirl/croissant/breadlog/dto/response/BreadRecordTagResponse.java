package sweetgirl.croissant.breadlog.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

import sweetgirl.croissant.breadlog.entity.BreadRecordTag;

public record BreadRecordTagResponse(
		Long id,
		Long photoId,
		int photoSortOrder,
		String name,
		String breadCategory,
		@JsonProperty("x") double positionX,
		@JsonProperty("y") double positionY,
		String style) {

	public static BreadRecordTagResponse from(BreadRecordTag tag) {
		return new BreadRecordTagResponse(
			tag.getId(),
			tag.getPhoto().getId(),
			tag.getPhoto().getSortOrder(),
			tag.getName(),
			tag.getBreadCategory(),
			tag.getPositionX(),
			tag.getPositionY(),
			tag.getStyle());
	}
}
