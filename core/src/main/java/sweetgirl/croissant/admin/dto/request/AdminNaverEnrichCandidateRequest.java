package sweetgirl.croissant.admin.dto.request;

import jakarta.validation.constraints.NotBlank;

public record AdminNaverEnrichCandidateRequest(
		@NotBlank String naverPlaceId,
		@NotBlank String name,
		String address,
		Double lat,
		Double lng) {
}
