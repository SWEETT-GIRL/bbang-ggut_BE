package sweetgirl.croissant.badge.dto.response;

import java.time.LocalDateTime;

import sweetgirl.croissant.badge.entity.Badge;
import sweetgirl.croissant.badge.entity.MemberBadge;

public record MemberBadgeResponse(
		String code,
		String imageUrl,
		String title,
		String hint,
		boolean achieved,
		LocalDateTime achievedAt,
		boolean celebrated) {

	public static MemberBadgeResponse of(Badge badge, MemberBadge memberBadge, String imageUrl) {
		return new MemberBadgeResponse(
			badge.getCode(),
			imageUrl,
			badge.getTitle(),
			badge.getHint(),
			memberBadge != null,
			memberBadge == null ? null : memberBadge.getAchievedAt(),
			memberBadge != null && memberBadge.getCelebratedAt() != null);
	}
}
