package sweetgirl.croissant.menu.dto.response;

import sweetgirl.croissant.global.dto.ImageResponse;
import sweetgirl.croissant.menu.entity.Menu;

public record StoreMenuResponse(
		Long menuId,
		String name,
		String category,
		ImageResponse image,
		Integer price,
		int likeCount,
		boolean isLiked,
		long reviewCount) {

	public static StoreMenuResponse of(Menu menu, ImageResponse image, boolean isLiked, long reviewCount) {
		return new StoreMenuResponse(
			menu.getId(),
			menu.getName(),
			menu.getMenuCategory().getName().name(),
			image,
			menu.getPrice(),
			menu.getLikeCount(),
			isLiked,
			reviewCount);
	}
}
