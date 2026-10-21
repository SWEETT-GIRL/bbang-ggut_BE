package sweetgirl.croissant.follow.dto.response;

import sweetgirl.croissant.member.entity.Member;

/** 팔로워 · 팔로잉 목록의 한 줄. 캐릭터 · 레벨 · 칭호는 프로필(P1-b)에서 필드를 더한다 — 지우거나 이름을 바꾸지 않는다. */
public record FollowMemberResponse(
		Long memberId,
		String nickname,
		/** 보는 사람이 이 사람을 팔로우하나 */
		boolean followedByMe,
		/** 이 사람이 보는 사람을 팔로우하나 */
		boolean followsMe) {

	public static FollowMemberResponse of(Member member, boolean followedByMe, boolean followsMe) {
		return new FollowMemberResponse(member.getId(), member.getNickname(), followedByMe, followsMe);
	}
}
