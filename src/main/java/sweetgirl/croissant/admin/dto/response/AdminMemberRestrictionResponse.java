package sweetgirl.croissant.admin.dto.response;

import sweetgirl.croissant.member.entity.MemberRestriction;

public record AdminMemberRestrictionResponse(
		Long restrictionId,
		Long memberId,
		String type,
		String reason,
		String startsAt,
		String endsAt,
		String createdAt) {

	public static AdminMemberRestrictionResponse from(MemberRestriction restriction) {
		return new AdminMemberRestrictionResponse(
			restriction.getId(),
			restriction.getMember().getId(),
			restriction.getRestrictionType().name(),
			restriction.getReason(),
			restriction.getStartsAt().toString(),
			restriction.getEndsAt() != null ? restriction.getEndsAt().toString() : null,
			restriction.getCreatedAt() != null ? restriction.getCreatedAt().toString() : null);
	}
}
