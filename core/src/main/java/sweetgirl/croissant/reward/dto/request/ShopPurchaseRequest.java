package sweetgirl.croissant.reward.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 상점 구매. 가격은 보내지 않는다 — 서버가 품목 표의 값으로 뺀다.
 *
 * @param characterCode 모습을 살 때 어느 친구의 것인지. 소품이면 보내지 않는다
 */
public record ShopPurchaseRequest(
		@NotBlank @Size(max = 40) String itemCode,
		@Size(max = 30) String characterCode) {
}
