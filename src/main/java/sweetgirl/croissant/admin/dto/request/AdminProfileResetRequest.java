package sweetgirl.croissant.admin.dto.request;

import jakarta.validation.constraints.Size;

public record AdminProfileResetRequest(
		@Size(max = 500) String reason) {
}
