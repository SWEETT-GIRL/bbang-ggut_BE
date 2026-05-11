package sweetgirl.croissant.menu.dto;

public record MenuSummaryDto(
		Long menuId,
		String name,
		String imageUrl) {
	public static MenuSummaryDto of(StoreMenuProjection projection, String imageUrl) {
		return new MenuSummaryDto(projection.getMenuId(), projection.getName(), imageUrl);
	}
}
