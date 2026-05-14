package sweetgirl.croissant.admin.dto.request;

import java.util.Map;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record AdminStoreCreateRequest(
		@NotBlank String name,
		@NotBlank String address,
		@NotNull CoordRequest coord,
		String phone,
		String kakaoPlaceId,
		@NotBlank String status,
		Map<String, BusinessHourRequest> hours) {

	public record CoordRequest(double latitude, double longitude) {
	}
}
