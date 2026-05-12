package sweetgirl.croissant.member.dto.request;

import jakarta.validation.constraints.NotNull;

public record ConsentUpdateRequest(
		@NotNull Boolean agreeToMarketing) {
}
