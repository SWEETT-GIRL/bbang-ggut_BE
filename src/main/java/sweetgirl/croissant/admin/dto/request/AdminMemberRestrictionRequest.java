package sweetgirl.croissant.admin.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record AdminMemberRestrictionRequest(
		@NotBlank String type,
		@Positive Integer durationDays,
		@Size(max = 500) String reason) {
}
