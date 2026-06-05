package sweetgirl.croissant.admin.service;

import java.time.LocalDateTime;
import java.util.Locale;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.admin.code.AdminErrorCode;
import sweetgirl.croissant.admin.dto.request.AdminModerationAppealReviewRequest;
import sweetgirl.croissant.admin.dto.response.AdminModerationAppealResponse;
import sweetgirl.croissant.admin.exception.AdminException;
import sweetgirl.croissant.member.entity.MemberRestriction;
import sweetgirl.croissant.member.repository.MemberRestrictionRepository;
import sweetgirl.croissant.report.entity.ModerationAction;
import sweetgirl.croissant.report.entity.ModerationAppeal;
import sweetgirl.croissant.report.enums.ModerationAppealStatus;
import sweetgirl.croissant.report.enums.ModerationTargetType;
import sweetgirl.croissant.report.enums.UserReportAction;
import sweetgirl.croissant.report.repository.ModerationAppealRepository;
import sweetgirl.croissant.review.entity.Review;
import sweetgirl.croissant.review.repository.ReviewRepository;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminModerationAppealService {

	private final ModerationAppealRepository moderationAppealRepository;
	private final ReviewRepository reviewRepository;
	private final MemberRestrictionRepository memberRestrictionRepository;

	public Page<AdminModerationAppealResponse> getAppeals(String status, int page, int size) {
		ModerationAppealStatus parsedStatus = toAppealStatus(status);
		PageRequest pageRequest = PageRequest.of(page, size);
		Page<ModerationAppeal> appeals = parsedStatus != null
			? moderationAppealRepository.findByStatus(parsedStatus, pageRequest)
			: moderationAppealRepository.findAllBy(pageRequest);
		return appeals.map(AdminModerationAppealResponse::from);
	}

	@Transactional
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
		UserReportAction moderationAction = action.getAction();
		switch (moderationAction) {
			case HIDE_REVIEW -> restoreHiddenReview(action);
			case DELETE_REVIEW -> restoreDeletedReview(action);
			case RESTRICT_MEMBER, SUSPEND_MEMBER -> releaseMemberRestriction(action);
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

	private ModerationAppealStatus toAppealStatus(String status) {
		if (status == null) {
			return null;
		}
		try {
			return ModerationAppealStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			throw new AdminException(AdminErrorCode.APPEAL_INVALID_STATUS);
		}
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
