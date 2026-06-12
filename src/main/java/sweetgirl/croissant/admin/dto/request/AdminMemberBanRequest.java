package sweetgirl.croissant.admin.dto.request;

import jakarta.validation.constraints.Size;

public record AdminMemberBanRequest(@Size(max = 500) String reason) {

	public String normalizedReason() {
		if (reason == null) {
			return null;
		}
		String normalized = reason.trim();
		return normalized.isEmpty() ? null : normalized;
	}
}
