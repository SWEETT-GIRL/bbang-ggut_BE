package sweetgirl.croissant.admin.dto.response;

import sweetgirl.croissant.abuse.entity.ModerationAction;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.entity.MemberRestriction;

public record AdminMemberBanResponse(
		Long memberId,
		Long restrictionId,
		Long actionId,
		String bannedAt) {

	public static AdminMemberBanResponse of(
			Member member,
			MemberRestriction restriction,
			ModerationAction action) {
		return new AdminMemberBanResponse(
			member.getId(),
			restriction.getId(),
			action.getId(),
			restriction.getCreatedAt().toString());
	}
}
