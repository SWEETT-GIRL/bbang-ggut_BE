package sweetgirl.croissant.store.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record StoreAddReportRequest(
		String naverPlaceId,
		@NotBlank String name,
		@NotBlank String address,
		String roadAddress,
		String phone,
		@NotNull Double latitude,
		@NotNull Double longitude,
		String reason) {
}
