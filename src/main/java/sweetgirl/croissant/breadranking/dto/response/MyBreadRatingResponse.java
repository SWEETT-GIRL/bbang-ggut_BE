package sweetgirl.croissant.breadranking.dto.response;

import java.time.LocalDateTime;
import java.util.List;

import sweetgirl.croissant.breadlog.entity.BreadRecordTag;
import sweetgirl.croissant.breadlog.enums.TasteChip;
import sweetgirl.croissant.breadlog.enums.TextureChip;

/**
 * 그 가게 × 빵 종류에 내가 남긴 최신 빵 평가 — 빵로그 태그 하나다. 태그가 없으면 줄의 rating 은 null.
 *
 * @param menuId 태그가 가리키는 가게 메뉴. 없으면 null — 앱은 가격 칸을 숨긴다
 * @param price  그 메뉴의 공동 가격
 */
public record MyBreadRatingResponse(
		Long tagId,
		Long recordId,
		LocalDateTime eatenAt,
		Long menuId,
		Integer price,
		Boolean again,
		List<TextureChip> textures,
		List<TasteChip> tastes,
		String memo) {

	public static MyBreadRatingResponse from(BreadRecordTag tag) {
		return new MyBreadRatingResponse(
			tag.getId(),
			tag.getRecord().getId(),
			tag.getRecord().getEatenAt(),
			tag.getMenu() == null ? null : tag.getMenu().getId(),
			tag.getMenu() == null ? null : tag.getMenu().getPrice(),
			tag.getAgain(),
			tag.textures(),
			tag.tastes(),
			tag.getRatingMemo());
	}
}
