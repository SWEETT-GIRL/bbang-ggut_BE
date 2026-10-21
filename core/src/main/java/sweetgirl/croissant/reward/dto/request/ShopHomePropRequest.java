package sweetgirl.croissant.reward.dto.request;

import jakarta.validation.constraints.Size;

/** @param itemCode 홈에 놓을 소품. null 이면 치운다 */
public record ShopHomePropRequest(@Size(max = 40) String itemCode) {
}
