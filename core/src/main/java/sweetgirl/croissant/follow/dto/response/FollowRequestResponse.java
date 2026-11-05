package sweetgirl.croissant.follow.dto.response;

import java.time.LocalDateTime;

import sweetgirl.croissant.follow.entity.MemberFollowRequest;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.profile.dto.response.CharacterFace;

/** 받은 팔로우 요청 한 줄 — 요청한 사람과 요청한 때. 필드는 더하기만. */
public record FollowRequestResponse(
		Long memberId,
		String nickname,
		/** 고른 캐릭터(없으면 마스코트). 세계관을 안 만든 사람은 null */
		String characterCode,
		String characterImageKey,
		LocalDateTime requestedAt) {

	public static FollowRequestResponse of(MemberFollowRequest request, CharacterFace face) {
		Member requester = request.getRequester();
		CharacterFace resolved = face == null ? CharacterFace.NONE : face;
		return new FollowRequestResponse(requester.getId(), requester.getNickname(), resolved.characterCode(),
			resolved.characterImageKey(), request.getCreatedAt());
	}
}
