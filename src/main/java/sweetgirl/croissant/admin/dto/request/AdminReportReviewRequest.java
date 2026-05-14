package sweetgirl.croissant.admin.dto.request;

import jakarta.validation.constraints.NotBlank;

public record AdminReportReviewRequest(
		@NotBlank String kind,
		@NotBlank String action) {
}
