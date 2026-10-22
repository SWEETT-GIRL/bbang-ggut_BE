package sweetgirl.croissant.member.dto.response;

import java.time.LocalDateTime;

import sweetgirl.croissant.member.entity.MemberBlock;
import sweetgirl.croissant.profile.dto.response.CharacterFace;

public record BlockResponse(
		Long memberId,
		String nickname,
		LocalDateTime blockedAt,
		/** 차단한 사람의 얼굴(10/22 추가) — 세계관이 없으면 null */
		String characterCode,
		String characterImageKey) {

	public static BlockResponse from(MemberBlock memberBlock) {
		return from(memberBlock, CharacterFace.NONE);
	}

	public static BlockResponse from(MemberBlock memberBlock, CharacterFace face) {
		CharacterFace resolved = face == null ? CharacterFace.NONE : face;
		return new BlockResponse(
			memberBlock.getBlockedMember().getId(),
			memberBlock.getBlockedMember().getNickname(),
			memberBlock.getCreatedAt(),
			resolved.characterCode(),
			resolved.characterImageKey());
	}
}
