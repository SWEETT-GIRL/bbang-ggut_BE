package sweetgirl.croissant.menu.dto.response;

import java.util.List;

import sweetgirl.croissant.global.dto.ImageResponse;
import sweetgirl.croissant.menu.entity.Menu;

public record MenuDetailResponse(
		Long storeId,
		String storeName,
		Long menuId,
		String menuName,
		String category,
		List<ImageResponse> images,
		Integer price,
		String description,
		int likeCount,
		boolean isLiked) {

	public static MenuDetailResponse of(Menu menu, List<ImageResponse> images, boolean isLiked) {
		return new MenuDetailResponse(
			menu.getStore().getId(),
			menu.getStore().getName(),
			menu.getId(),
			menu.getName(),
			menu.getMenuCategory() != null ? menu.getMenuCategory().getName().name() : null,
			images,
			menu.getPrice(),
			menu.getDescription(),
			menu.getLikeCount(),
			isLiked);
	}
}
