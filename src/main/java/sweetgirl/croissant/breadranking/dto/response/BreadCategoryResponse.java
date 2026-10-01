package sweetgirl.croissant.breadranking.dto.response;

import sweetgirl.croissant.breadranking.entity.BreadCategory;

/**
 * 빵 종류 한 줄.
 *
 * @param menuKind   기본 목록이면 MenuKind 이름 — 빵로그 태그의 breadCategory 와 같은 값. 사용자 생성은 null
 * @param breadGroup 대분류(칩 순서용). 없으면 null
 * @param voters     이 종류에서 1등을 매긴 사람 수(공용 목록 정렬 기준)
 */
public record BreadCategoryResponse(
		Long id,
		String name,
		String menuKind,
		String breadGroup,
		boolean isDefault,
		long voters) {

	public static BreadCategoryResponse of(BreadCategory category, long voters) {
		return new BreadCategoryResponse(
			category.getId(),
			category.getName(),
			category.getMenuKind() == null ? null : category.getMenuKind().name(),
			category.getBreadGroup() == null ? null : category.getBreadGroup().name(),
			category.isDefault(),
			voters);
	}
}
