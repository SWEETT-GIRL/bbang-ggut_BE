package sweetgirl.croissant.breadlog.dto.request;

import java.time.LocalDateTime;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record TutorialRecordRequest(
		@NotBlank String templateCode,
		@NotBlank String towerBread,
		@NotNull LocalDateTime eatenAt,
		@Size(max = 500) String memo,
		@DecimalMin("0.0") @DecimalMax("1.0") double x,
		@DecimalMin("0.0") @DecimalMax("1.0") double y,
		@Size(max = 50) String style,
		@Valid BreadTagRatingRequest rating) {
}
