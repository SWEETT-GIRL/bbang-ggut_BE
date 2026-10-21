package sweetgirl.croissant.admin.dto.response;

import java.math.BigDecimal;
import java.util.List;

import sweetgirl.croissant.abuse.entity.AbuseReport;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.review.entity.Review;

public record AdminAbuseReportDetailResponse(
		Long reportId,
		String kind,
		String targetType,
		Long targetId,
		String reasonType,
		String reasonDetail,
		String status,
		String moderationAction,
		Long reporterId,
		Long reportedMemberId,
		String createdAt,
		String reviewedAt,
		Object target) {

	public static AdminAbuseReportDetailResponse of(AbuseReport report, Object target) {
		return new AdminAbuseReportDetailResponse(
			report.getId(),
			"ABUSE_REPORT",
			report.getTargetType().name(),
			report.getTargetId(),
			report.getReasonType().name(),
			report.getReasonDetail(),
			report.getStatus().name(),
			report.getModerationAction().name(),
			report.getReporter().getId(),
			report.getReportedMember().getId(),
			report.getCreatedAt() != null ? report.getCreatedAt().toString() : null,
			report.getReviewedAt() != null ? report.getReviewedAt().toString() : null,
			target);
	}

	public record ReviewTarget(
			Long reviewId,
			Long menuId,
			String menuName,
			Long storeId,
			String storeName,
			Long authorId,
			BigDecimal rating,
			String content,
			List<String> images,
			String createdAt,
			String hiddenAt,
			String hiddenReason,
			String deletedAt) {

		public static ReviewTarget of(Review review, List<String> images) {
			return new ReviewTarget(
				review.getId(),
				review.getMenu().getId(),
				review.getMenu().getName(),
				review.getMenu().getStore().getId(),
				review.getMenu().getStore().getName(),
				review.getMember().getId(),
				review.getRating(),
				review.getContent(),
				images,
				review.getCreatedAt() != null ? review.getCreatedAt().toString() : null,
				review.getHiddenAt() != null ? review.getHiddenAt().toString() : null,
				review.getHiddenReason(),
				review.getDeletedAt() != null ? review.getDeletedAt().toString() : null);
		}
	}

	public record MemberProfileTarget(
			Long memberId,
			String nickname,
			String createdAt,
			String deletedAt) {

		public static MemberProfileTarget from(Member member) {
			return new MemberProfileTarget(
				member.getId(),
				member.getNickname(),
				member.getCreatedAt() != null ? member.getCreatedAt().toString() : null,
				member.getDeletedAt() != null ? member.getDeletedAt().toString() : null);
		}
	}
}
