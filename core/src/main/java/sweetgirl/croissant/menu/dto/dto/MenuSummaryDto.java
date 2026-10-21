package sweetgirl.croissant.menu.dto.dto;

import sweetgirl.croissant.menu.dto.projection.StoreMenuProjection;

public record MenuSummaryDto(
		Long menuId,
		String name,
		String thumbnailImageKey,
		boolean matched) {

	public static MenuSummaryDto of(StoreMenuProjection projection, String thumbnailImageKey) {
		return of(projection, thumbnailImageKey, false);
	}

	/**
	 * @param matched 검색어에 직접 매칭된 메뉴인지 여부. 메뉴 검색이 없는 지금은 항상 false 이지만 응답 계약 호환을 위해 남긴다.
	 *                검색이 아닌 일반 조회에서는 {@code false}.
	 */
	public static MenuSummaryDto of(StoreMenuProjection projection, String thumbnailImageKey, boolean matched) {
		return new MenuSummaryDto(projection.getMenuId(), projection.getName(), thumbnailImageKey, matched);
	}
}
