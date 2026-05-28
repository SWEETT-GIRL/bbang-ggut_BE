package sweetgirl.croissant.admin.dto.request;

import jakarta.validation.constraints.NotBlank;

public record AdminNaverPlaceIdUpdateRequest(
		@NotBlank String naverPlaceId) {
}
