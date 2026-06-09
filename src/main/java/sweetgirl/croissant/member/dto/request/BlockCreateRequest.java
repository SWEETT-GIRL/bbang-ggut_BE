package sweetgirl.croissant.member.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record BlockCreateRequest(
		@NotNull @Positive Long blockedMemberId) {
}
