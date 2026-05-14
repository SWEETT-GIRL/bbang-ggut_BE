package sweetgirl.croissant.admin.dto.request;

import jakarta.validation.constraints.NotBlank;

public record AdminMenuCreateRequest(
		Long bakeryId,
		@NotBlank String name,
		String price,
		String desc,
		String category,
		String status) {
}
