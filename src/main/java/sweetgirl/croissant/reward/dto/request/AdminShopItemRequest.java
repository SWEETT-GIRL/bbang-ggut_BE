package sweetgirl.croissant.reward.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import sweetgirl.croissant.reward.enums.ShopItemKind;

/**
 * 상점 품목 만들기·고치기. 코드가 없던 것이면 만들고, 있던 것이면 고친다.
 *
 * @param kind 만들 때만 본다. 이미 있는 품목의 종류는 바꾸지 않는다
 */
public record AdminShopItemRequest(
		@NotNull ShopItemKind kind,
		@Size(max = 20) String pose,
		@NotBlank @Size(max = 30) String name,
		@NotNull @Min(1) Integer price,
		@Min(1) Integer lockLevel,
		@NotNull Integer sortOrder,
		boolean isNew,
		Boolean active) {
}
