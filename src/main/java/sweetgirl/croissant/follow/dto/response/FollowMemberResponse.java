package sweetgirl.croissant.follow.dto.response;

import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.profile.dto.response.CharacterFace;

/**
 * 팔로워 · 팔로잉 · 찾기 · 추천 · 좋아요 누른 사람 목록의 한 줄. 얼굴(characterCode · characterImageKey)은 2026-10-22 에 더했다 —
 * 지우거나 이름을 바꾸지 않는다.
 */
public record FollowMemberResponse(
		Long memberId,
		String nickname,
		/** 보는 사람이 이 사람을 팔로우하나 */
		boolean followedByMe,
		/** 이 사람이 보는 사람을 팔로우하나 */
		boolean followsMe,
		/** 고른 캐릭터(없으면 마스코트). 세계관을 안 만든 사람은 null */
		String characterCode,
		String characterImageKey,
		/** 비공개 계정인가(2026-11-04) — 자물쇠 표시 */
		boolean isPrivate,
		/** 보는 사람이 팔로우 요청을 보내 두고 수락을 기다리는 중인가 — 버튼이 "요청됨" */
		boolean followRequestedByMe) {
	public static FollowMemberResponse of(Member member, boolean followedByMe, boolean followsMe) {
		return of(member, followedByMe, followsMe, CharacterFace.NONE);
	}

	public static FollowMemberResponse of(Member member, boolean followedByMe, boolean followsMe, CharacterFace face) {
		return of(member, followedByMe, followsMe, face, false);
	}

	public static FollowMemberResponse of(Member member, boolean followedByMe, boolean followsMe, CharacterFace face,
			boolean followRequestedByMe) {
		CharacterFace resolved = face == null ? CharacterFace.NONE : face;
		return new FollowMemberResponse(member.getId(), member.getNickname(), followedByMe, followsMe,
			resolved.characterCode(), resolved.characterImageKey(), member.isPrivate(), followRequestedByMe);
	}
}
