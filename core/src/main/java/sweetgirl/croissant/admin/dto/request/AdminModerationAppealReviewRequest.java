package sweetgirl.croissant.admin.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AdminModerationAppealReviewRequest(
		@NotBlank String action,
		@Size(max = 1000) String adminMemo) {

	public String normalizedAdminMemo() {
		if (adminMemo == null) {
			return null;
		}
		String normalized = adminMemo.trim();
		return normalized.isEmpty() ? null : normalized;
	}
}
