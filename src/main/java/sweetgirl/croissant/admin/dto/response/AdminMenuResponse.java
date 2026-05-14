package sweetgirl.croissant.admin.dto.response;

import java.util.List;

import sweetgirl.croissant.menu.entity.Menu;

public record AdminMenuResponse(
		Long id,
		Long bakeryId,
		String name,
		String price,
		String desc,
		int likes,
		String category,
		List<String> photos,
		Integer order,
		String status,
		String createdAt) {

	public static AdminMenuResponse of(Menu menu, List<String> photoUrls) {
		String priceStr = menu.getPrice() != null ? menu.getPrice() + "원" : null;
		String statusStr = menu.getDeletedAt() == null ? "active" : "inactive";
		return new AdminMenuResponse(
			menu.getId(),
			menu.getStore().getId(),
			menu.getName(),
			priceStr,
			menu.getDescription(),
			menu.getRecommendCount(),
			menu.getMenuCategory().getName(),
			photoUrls,
			null,
			statusStr,
			menu.getCreatedAt() != null ? menu.getCreatedAt().toString() : null);
	}
}
