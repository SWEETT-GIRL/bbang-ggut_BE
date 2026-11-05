package sweetgirl.croissant.comment.dto.response;

import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.profile.dto.response.CharacterFace;

/** 멘션 후보 한 줄. {@code relation} 은 FOLLOWING · FOLLOWER · COMMENTER 중 가장 가까운 것 — 앱이 구분 글씨를 단다. */
public record MentionCandidateResponse(Long memberId, String nickname, String characterCode, String characterImageKey,
		String relation) {

	public static MentionCandidateResponse of(Member member, CharacterFace face, String relation) {
		return new MentionCandidateResponse(member.getId(), member.getNickname(), face.characterCode(),
			face.characterImageKey(), relation);
	}
}
