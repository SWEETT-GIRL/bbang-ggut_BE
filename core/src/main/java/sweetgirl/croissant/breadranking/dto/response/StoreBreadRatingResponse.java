package sweetgirl.croissant.breadranking.dto.response;

import java.util.List;

/**
 * 가게 상세의 빵 종류별 평가 한 줄 — 평가한 사람 5명 이상인 종류만.
 *
 * @param rank      전국 등수. 순위가 안 열렸거나 100위 밖이면 null
 * @param raters    평가한 사람 수
 * @param againRate 또 먹을래요 비율(0~1). 고른 사람이 없으면 null
 * @param textures  식감 상위(비율 순). 비율 = 그 칩을 고른 태그 / 식감을 하나라도 고른 태그
 * @param menuId    가장 많이 태그된 그 가게 메뉴. 없으면 null
 * @param price     그 메뉴의 공동 가격
 */
public record StoreBreadRatingResponse(
		BreadCategoryResponse category,
		Integer rank,
		long raters,
		Double againRate,
		List<TextureStat> textures,
		Long menuId,
		Integer price) {

	public record TextureStat(String code, String label, double ratio) {
	}
}
