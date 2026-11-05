package sweetgirl.croissant.admin.service;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.abuse.entity.ModerationAction;
import sweetgirl.croissant.abuse.entity.ModerationAppeal;
import sweetgirl.croissant.abuse.enums.AbuseReportAction;
import sweetgirl.croissant.abuse.enums.ModerationTargetType;
import sweetgirl.croissant.abuse.repository.ModerationAppealRepository;
import sweetgirl.croissant.admin.code.AdminErrorCode;
import sweetgirl.croissant.admin.dto.request.AdminModerationAppealReviewRequest;
import sweetgirl.croissant.admin.dto.response.AdminModerationAppealResponse;
import sweetgirl.croissant.admin.exception.AdminException;
import sweetgirl.croissant.breadlog.repository.BreadRecordRepository;
import sweetgirl.croissant.comment.repository.BreadRecordCommentRepository;
import sweetgirl.croissant.member.entity.MemberRestriction;
import sweetgirl.croissant.member.repository.MemberRestrictionRepository;
import sweetgirl.croissant.review.entity.Review;
import sweetgirl.croissant.review.repository.ReviewRepository;

@Service
@RequiredArgsConstructor
@Transactional
public class AdminModerationAppealCommandService {

	private final ModerationAppealRepository moderationAppealRepository;
	private final ReviewRepository reviewRepository;
	private final MemberRestrictionRepository memberRestrictionRepository;
	private final BreadRecordRepository breadRecordRepository;
	private final BreadRecordCommentRepository commentRepository;

	public AdminModerationAppealResponse reviewAppeal(
			Long appealId,
			AdminModerationAppealReviewRequest request) {
		ModerationAppeal appeal = moderationAppealRepository.findWithDetailsById(appealId)
			.orElseThrow(() -> new AdminException(AdminErrorCode.APPEAL_NOT_FOUND));
		if (!appeal.isPending()) {
			throw new AdminException(AdminErrorCode.APPEAL_ALREADY_REVIEWED);
		}

		if (toReviewAction(request.action())) {
			applyAcceptedAppeal(appeal.getModerationAction());
			appeal.accept(request.normalizedAdminMemo());
		} else {
			appeal.reject(request.normalizedAdminMemo());
		}
		return AdminModerationAppealResponse.from(appeal);
	}

	private void applyAcceptedAppeal(ModerationAction action) {
		AbuseReportAction moderationAction = action.getAction();
		switch (moderationAction) {
			case HIDE_REVIEW -> restoreHiddenReview(action);
			case DELETE_REVIEW -> restoreDeletedReview(action);
			case RESTRICT_MEMBER, SUSPEND_MEMBER -> releaseMemberRestriction(action);
			case HIDE_RECORD -> breadRecordRepository.findById(action.getTargetId())
				.orElseThrow(() -> new AdminException(AdminErrorCode.REPORT_TARGET_NOT_FOUND))
				.restoreHidden();
			case HIDE_COMMENT -> commentRepository.findById(action.getTargetId())
				.orElseThrow(() -> new AdminException(AdminErrorCode.REPORT_TARGET_NOT_FOUND))
				.restoreHidden();
			case DELETE_COMMENT -> commentRepository.findById(action.getTargetId())
				.orElseThrow(() -> new AdminException(AdminErrorCode.REPORT_TARGET_NOT_FOUND))
				.restoreDeleted();
			case RESET_PROFILE -> {
				// 초기 닉네임은 별도 저장하지 않으므로 자동 복구 대신 승인 상태와 관리자 메모만 남긴다.
			}
			default -> throw new AdminException(AdminErrorCode.APPEAL_INVALID_ACTION);
		}
	}

	private void restoreHiddenReview(ModerationAction action) {
		Review review = findReviewTarget(action);
		review.restoreHidden();
	}

	private void restoreDeletedReview(ModerationAction action) {
		Review review = findReviewTarget(action);
		review.restoreModeration();
	}

	private Review findReviewTarget(ModerationAction action) {
		if (action.getTargetType() != ModerationTargetType.REVIEW) {
			throw new AdminException(AdminErrorCode.REPORT_TARGET_TYPE_MISMATCH);
		}
		return reviewRepository.findByIdWithDetails(action.getTargetId())
			.orElseThrow(() -> new AdminException(AdminErrorCode.REPORT_TARGET_NOT_FOUND));
	}

	private void releaseMemberRestriction(ModerationAction action) {
		if (action.getTargetType() != ModerationTargetType.MEMBER_RESTRICTION) {
			throw new AdminException(AdminErrorCode.REPORT_TARGET_TYPE_MISMATCH);
		}
		MemberRestriction restriction = memberRestrictionRepository.findById(action.getTargetId())
			.orElseThrow(() -> new AdminException(AdminErrorCode.REPORT_TARGET_NOT_FOUND));
		restriction.release(LocalDateTime.now());
	}

	private boolean toReviewAction(String action) {
		if ("ACCEPT".equalsIgnoreCase(action)) {
			return true;
		}
		if ("REJECT".equalsIgnoreCase(action)) {
			return false;
		}
		throw new AdminException(AdminErrorCode.APPEAL_INVALID_ACTION);
	}
}
