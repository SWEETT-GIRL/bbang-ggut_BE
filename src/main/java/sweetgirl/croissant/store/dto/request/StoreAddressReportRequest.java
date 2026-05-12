package sweetgirl.croissant.store.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record StoreAddressReportRequest(
		@NotBlank String address,
		String roadAddress,
		String placeName,
		@NotNull Double latitude,
		@NotNull Double longitude,
		String reason) {
}
