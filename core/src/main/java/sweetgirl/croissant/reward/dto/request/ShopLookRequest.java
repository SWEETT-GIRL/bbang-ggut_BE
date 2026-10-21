package sweetgirl.croissant.reward.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** @param itemCode 입힐 모습. null 이면 기본 모습으로 돌아간다 */
public record ShopLookRequest(
		@NotBlank @Size(max = 30) String characterCode,
		@Size(max = 40) String itemCode) {
}
