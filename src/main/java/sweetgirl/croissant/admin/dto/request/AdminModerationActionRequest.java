package sweetgirl.croissant.admin.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AdminModerationActionRequest(
		@NotBlank String action,
		@Size(max = 500) String reason,
		Boolean notifyUser) {
}
