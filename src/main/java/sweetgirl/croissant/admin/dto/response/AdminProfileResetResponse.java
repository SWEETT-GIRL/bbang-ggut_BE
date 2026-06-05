package sweetgirl.croissant.admin.dto.response;

import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.report.entity.ModerationAction;

public record AdminProfileResetResponse(
		Long memberId,
		String nickname,
		Long actionId,
		String reason,
		String createdAt) {

	public static AdminProfileResetResponse of(Member member, ModerationAction moderationAction) {
		return new AdminProfileResetResponse(
			member.getId(),
			member.getNickname(),
			moderationAction.getId(),
			moderationAction.getReason(),
			moderationAction.getCreatedAt() != null ? moderationAction.getCreatedAt().toString() : null);
	}
}
