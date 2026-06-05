package sweetgirl.croissant.admin.dto.request;

import java.time.LocalDateTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.entity.MemberRestriction;
import sweetgirl.croissant.member.enums.MemberRestrictionType;
import sweetgirl.croissant.report.entity.ModerationAction;
import sweetgirl.croissant.report.enums.ModerationTargetType;
import sweetgirl.croissant.report.enums.UserReportAction;

public record AdminMemberRestrictionRequest(
		@NotBlank String type,
		@Positive Integer durationDays,
		@Size(max = 500) String reason) {

	public MemberRestriction toRestriction(
			Member member,
			MemberRestrictionType restrictionType,
			LocalDateTime startsAt,
			LocalDateTime endsAt) {
		return new MemberRestriction(member, restrictionType, normalizedReason(), startsAt, endsAt);
	}

	public ModerationAction toModerationAction(
			Member member,
			MemberRestriction restriction,
			UserReportAction action) {
		return new ModerationAction(
			null,
			ModerationTargetType.MEMBER_RESTRICTION,
			restriction.getId(),
			member,
			action,
			normalizedReason());
	}

	private String normalizedReason() {
		if (reason == null) {
			return null;
		}
		String normalized = reason.trim();
		return normalized.isEmpty() ? null : normalized;
	}
}
