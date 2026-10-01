package sweetgirl.croissant.breadranking.dto.response;

import java.util.List;

import sweetgirl.croissant.breadranking.enums.TasteChip;
import sweetgirl.croissant.breadranking.enums.TextureChip;

/**
 * 빵 평가 칩 목록 — 앞 6개가 그 빵 종류(대분류)에서 펼쳐 보이는 것, 나머지는 "더보기". 전체가 다 들어 있다.
 *
 * @param breadGroup 대분류 이름. 빵·기타처럼 대분류가 없으면 null(기본 순서)
 */
public record BreadRatingChipsResponse(String breadGroup, List<Chip> textures, List<Chip> tastes) {

	/** @param code 요청에 보내는 값(enum 이름) · @param label 화면 글자 */
	public record Chip(String code, String label) {

		public static Chip of(TextureChip texture) {
			return new Chip(texture.name(), texture.getLabel());
		}

		public static Chip of(TasteChip taste) {
			return new Chip(taste.name(), taste.getLabel());
		}
	}
}
