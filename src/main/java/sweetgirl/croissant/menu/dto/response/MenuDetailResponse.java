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
		int recommendCount) {

	public static MenuDetailResponse of(Menu menu, List<ImageResponse> images) {
		return new MenuDetailResponse(
			menu.getStore().getId(),
			menu.getStore().getName(),
			menu.getId(),
			menu.getName(),
			menu.getMenuCategory().getName().name(),
			images,
			menu.getPrice(),
			menu.getDescription(),
			menu.getRecommendCount());
	}
}
