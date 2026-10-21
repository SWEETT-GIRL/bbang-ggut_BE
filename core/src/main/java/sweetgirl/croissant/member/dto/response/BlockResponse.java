package sweetgirl.croissant.member.dto.response;

import java.time.LocalDateTime;

import sweetgirl.croissant.member.entity.MemberBlock;

public record BlockResponse(
		Long memberId,
		String nickname,
		LocalDateTime blockedAt) {

	public static BlockResponse from(MemberBlock memberBlock) {
		return new BlockResponse(
			memberBlock.getBlockedMember().getId(),
			memberBlock.getBlockedMember().getNickname(),
			memberBlock.getCreatedAt());
	}
}
