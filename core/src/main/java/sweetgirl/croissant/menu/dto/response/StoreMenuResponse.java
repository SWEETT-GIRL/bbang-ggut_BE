package sweetgirl.croissant.menu.dto.response;

import sweetgirl.croissant.global.dto.ImageResponse;
import sweetgirl.croissant.menu.entity.Menu;

public record StoreMenuResponse(
		Long menuId,
		String name,
		String description,
		String category,
		ImageResponse image,
		Integer price,
		int likeCount,
		boolean isLiked,
		long reviewCount,
		/** 메뉴 등록 응답에서만 — AI 가 제안한 kind 를 칩으로 보여 주기 위해. 목록·상세 응답은 null */
		String suggestedKind) {

	public static StoreMenuResponse of(Menu menu, ImageResponse image, boolean isLiked, long reviewCount) {
		return new StoreMenuResponse(
			menu.getId(),
			menu.getName(),
			menu.getDescription(),
			menu.getMenuCategory() != null ? menu.getMenuCategory().getName().name() : null,
			image,
			menu.getPrice(),
			menu.getLikeCount(),
			isLiked,
			reviewCount,
			null);
	}

	public StoreMenuResponse withSuggestedKind(String suggestedKind) {
		return new StoreMenuResponse(menuId, name, description, category, image, price, likeCount, isLiked,
			reviewCount, suggestedKind);
	}
}
