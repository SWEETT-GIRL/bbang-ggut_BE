package sweetgirl.croissant.menu.dto.response;

import sweetgirl.croissant.menu.entity.MenuCategory;

public record MenuCategoryResponse(
		Long categoryId,
		String name) {

	public static MenuCategoryResponse from(MenuCategory category) {
		return new MenuCategoryResponse(category.getId(), category.getName().name());
	}
}
