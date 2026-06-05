package sweetgirl.croissant.member.dto.response;

import sweetgirl.croissant.member.entity.Member;

public record MemberProfileResponse(Long memberId, String nickname) {

	public static MemberProfileResponse from(Member member) {
		return new MemberProfileResponse(member.getId(), member.getNickname());
	}
}
