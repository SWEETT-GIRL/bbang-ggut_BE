package sweetgirl.croissant.admin.dto.response;

import java.math.BigDecimal;
import java.util.List;

import sweetgirl.croissant.abuse.entity.AbuseReport;
import sweetgirl.croissant.breadlog.entity.BreadRecord;
import sweetgirl.croissant.comment.entity.BreadRecordComment;
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

	/** 빵로그 신고 미리보기 — 후기 · 사진 키 · 공개 여부 · 숨김 상태. */
	public record RecordTarget(
			Long recordId,
			Long authorId,
			String storeName,
			String memo,
			List<String> photos,
			boolean isPublic,
			String createdAt,
			String hiddenAt,
			String hiddenReason,
			String hiddenBy) {

		public static RecordTarget of(BreadRecord record, List<String> photos) {
			return new RecordTarget(
				record.getId(),
				record.getMember().getId(),
				record.getStoreName(),
				record.getMemo(),
				photos,
				record.isPublic(),
				record.getCreatedAt() != null ? record.getCreatedAt().toString() : null,
				record.getHiddenAt() != null ? record.getHiddenAt().toString() : null,
				record.getHiddenReason(),
				record.getHiddenBy());
		}
	}

	/** 댓글 신고 미리보기. */
	public record CommentTarget(
			Long commentId,
			Long recordId,
			Long authorId,
			String content,
			String createdAt,
			String deletedAt,
			String deletedBy,
			String hiddenAt,
			String hiddenReason,
			String hiddenBy) {

		public static CommentTarget from(BreadRecordComment comment) {
			return new CommentTarget(
				comment.getId(),
				comment.getRecord().getId(),
				comment.getMember().getId(),
				comment.getContent(),
				comment.getCreatedAt() != null ? comment.getCreatedAt().toString() : null,
				comment.getDeletedAt() != null ? comment.getDeletedAt().toString() : null,
				comment.getDeletedBy(),
				comment.getHiddenAt() != null ? comment.getHiddenAt().toString() : null,
				comment.getHiddenReason(),
				comment.getHiddenBy());
		}
	}
}
