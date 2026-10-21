package sweetgirl.croissant.reward.dto.response;

import java.util.List;

import sweetgirl.croissant.reward.entity.ShopItem;
import sweetgirl.croissant.reward.enums.ShopItemKind;

/**
 * 상점 품목. 회원과 무관한 전역 카탈로그다.
 *
 * @param version 들고 있는 값과 다르면 앱이 품목을 다시 받는다
 */
public record ShopCatalogResponse(int version, List<Item> items) {

	/**
	 * @param pose      모습(POSE)일 때 그림의 포즈 이름. 소품이면 null
	 * @param lockLevel 이 레벨부터 살 수 있다. 누구나 살 수 있으면 null
	 */
	public record Item(
			String code,
			ShopItemKind kind,
			String pose,
			String name,
			int price,
			Integer lockLevel,
			int sortOrder,
			boolean isNew) {

		public static Item from(ShopItem item) {
			return new Item(item.getCode(), item.getKind(), item.getPose(), item.getName(), item.getPrice(),
				item.getLockLevel(), item.getSortOrder(), item.isNew());
		}
	}
}
