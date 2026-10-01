package sweetgirl.croissant.breadlog.dto.response;

import java.util.List;

import sweetgirl.croissant.breadlog.entity.BreadRecordTag;
import sweetgirl.croissant.breadranking.enums.TasteChip;
import sweetgirl.croissant.breadranking.enums.TextureChip;

/** 태그 하나의 빵 평가. 평가가 하나도 없는 태그는 응답에서 null 이다. */
public record BreadTagRatingResponse(
		Boolean again,
		List<TextureChip> textures,
		List<TasteChip> tastes,
		String memo) {

	public static BreadTagRatingResponse from(BreadRecordTag.Rating rating) {
		return new BreadTagRatingResponse(rating.again(), rating.textures(), rating.tastes(), rating.memo());
	}
}
