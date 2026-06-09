package sweetgirl.croissant.report.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.report.entity.ModerationAction;
import sweetgirl.croissant.report.entity.ModerationAppeal;

public record ModerationAppealCreateRequest(
		@NotNull @Positive Long actionId,
		@NotBlank @Size(max = 1000) String content) {

	public ModerationAppeal toAppeal(ModerationAction moderationAction, Member member) {
		return new ModerationAppeal(moderationAction, member, normalizedContent());
	}

	public String normalizedContent() {
		String normalized = content.trim();
		return normalized.isEmpty() ? null : normalized;
	}
}
