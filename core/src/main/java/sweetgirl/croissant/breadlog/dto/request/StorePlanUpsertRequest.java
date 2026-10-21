package sweetgirl.croissant.breadlog.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record StorePlanUpsertRequest(
		@NotBlank @Size(max = 200) String memo) {
}
