package sweetgirl.croissant.admin.service;

import java.util.Locale;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.abuse.entity.AbuseReport;
import sweetgirl.croissant.abuse.entity.ModerationAction;
import sweetgirl.croissant.abuse.enums.AbuseReportAction;
import sweetgirl.croissant.abuse.enums.AbuseReportStatus;
import sweetgirl.croissant.abuse.enums.AbuseReportTargetType;
import sweetgirl.croissant.abuse.repository.AbuseReportRepository;
import sweetgirl.croissant.abuse.repository.ModerationActionRepository;
import sweetgirl.croissant.admin.code.AdminErrorCode;
import sweetgirl.croissant.admin.dto.request.AdminModerationActionRequest;
import sweetgirl.croissant.admin.dto.request.AdminReportReviewRequest;
import sweetgirl.croissant.admin.dto.response.AdminModerationActionResponse;
import sweetgirl.croissant.admin.exception.AdminException;
import sweetgirl.croissant.alarm.enums.ReportNotificationAction;
import sweetgirl.croissant.alarm.service.ReportNotificationService;
import sweetgirl.croissant.breadlog.entity.BreadRecord;
import sweetgirl.croissant.breadlog.repository.BreadRecordRepository;
import sweetgirl.croissant.comment.entity.BreadRecordComment;
import sweetgirl.croissant.comment.repository.BreadRecordCommentRepository;
import sweetgirl.croissant.common.enums.ReportKind;
import sweetgirl.croissant.contribution.entity.ContributionReport;
import sweetgirl.croissant.contribution.entity.MenuContributionReport;
import sweetgirl.croissant.contribution.entity.StoreContributionReport;
import sweetgirl.croissant.contribution.enums.ContributionReportStatus;
import sweetgirl.croissant.contribution.repository.MenuContributionReportRepository;
import sweetgirl.croissant.contribution.repository.StoreContributionReportRepository;
import sweetgirl.croissant.review.entity.Review;
import sweetgirl.croissant.review.repository.ReviewRepository;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.enums.StoreReportStatus;
import sweetgirl.croissant.store.enums.StoreReportType;

@Service
@RequiredArgsConstructor
@Transactional
public class AdminReportCommandService {

	private static final String HIDDEN_BY_ADMIN = "ADMIN";

	private final StoreContributionReportRepository storeContributionReportRepository;
	private final MenuContributionReportRepository menuContributionReportRepository;
	private final AbuseReportRepository abuseReportRepository;
	private final ModerationActionRepository moderationActionRepository;
	private final ReviewRepository reviewRepository;
	private final BreadRecordRepository breadRecordRepository;
	private final BreadRecordCommentRepository commentRepository;
	private final ReportNotificationService reportNotificationService;
	private final ContributionReportProcessor contributionReportProcessor;

	public AdminModerationActionResponse applyModerationAction(
			Long reportId,
			AdminModerationActionRequest request) {
		AbuseReport report = abuseReportRepository.findByIdWithMembers(reportId)
			.orElseThrow(() -> new AdminException(AdminErrorCode.REPORT_NOT_FOUND));
		if (report.getStatus() != AbuseReportStatus.ACCEPTED) {
			throw new AdminException(AdminErrorCode.REPORT_MODERATION_NOT_ALLOWED);
		}

		AbuseReportAction action = toModerationAction(request.action());
		String reason = request.moderationReason();
		applyTargetModeration(report, action, reason);
		report.applyModerationAction(action);

		ModerationAction savedAction = moderationActionRepository.save(request.toModerationAction(report, action));
		if (Boolean.TRUE.equals(request.notifyUser()) && shouldNotifyModeratedMember(action)) {
			reportNotificationService.notifyModerationAction(savedAction);
		}
		return AdminModerationActionResponse.from(savedAction);
	}

	public void reviewReport(Long reportId, AdminReportReviewRequest request) {
		boolean accept = toAction(request.action());
		if (request.kind() == null) {
			throw new AdminException(AdminErrorCode.REPORT_INVALID_KIND);
		}

		switch (request.kind()) {
			case "MENU_ADD_REPORT" -> reviewMenuAddReport(reportId, accept);
			case "STORE_ADD_REPORT" -> reviewStoreAddReport(reportId, accept);
			case "STORE_REPORT" -> reviewStoreReport(reportId, accept);
			case "MENU_REPORT" -> reviewMenuReport(reportId, accept);
			case "ABUSE_REPORT" -> reviewAbuseReport(reportId, accept);
			default -> throw new AdminException(AdminErrorCode.REPORT_INVALID_KIND);
		}
	}

	private void reviewMenuAddReport(Long reportId, boolean accept) {
		MenuContributionReport contribution = menuContributionReportRepository.findByContributionReportId(reportId)
			.orElseThrow(() -> new AdminException(AdminErrorCode.REPORT_NOT_FOUND));
		assertContributionPending(contribution.getContributionReport(), contribution.getStatus());
		if (accept) {
			contributionReportProcessor.applyMenuAddReportApprove(contribution);
			contribution.approve();
			contribution.getContributionReport().approve();
		} else {
			contributionReportProcessor.cleanupMenuAddReportImages(contribution);
			contribution.reject();
			contribution.getContributionReport().reject();
		}
		reportNotificationService.notifyContributionReportResult(
			contribution.getMember(), contribution.getStore(), null, contribution.getContributionReport().getId(),
			contribution.getContributionReport().getId(),
			ReportKind.MENU_ADD_REPORT,
			accept ? ReportNotificationAction.APPROVED : ReportNotificationAction.REJECTED,
			"메뉴 추가");
	}

	private void reviewStoreAddReport(Long reportId, boolean accept) {
		StoreContributionReport contribution = storeContributionReportRepository
			.findByContributionReportId(reportId)
			.orElseThrow(() -> new AdminException(AdminErrorCode.REPORT_NOT_FOUND));
		assertContributionPending(contribution.getContributionReport(), contribution.getStatus());
		Store store = null;
		if (accept) {
			store = contributionReportProcessor.applyStoreAddReportApprove(contribution);
			contribution.approve();
			contribution.getContributionReport().approve();
		} else {
			contribution.reject();
			contribution.getContributionReport().reject();
		}
		reportNotificationService.notifyContributionReportResult(
			contribution.getMember(), store, contribution.getName(), contribution.getContributionReport().getId(),
			contribution.getContributionReport().getId(),
			ReportKind.STORE_ADD_REPORT,
			accept ? ReportNotificationAction.APPROVED : ReportNotificationAction.REJECTED,
			"가게 추가");
	}

	private void reviewStoreReport(Long reportId, boolean accept) {
		StoreContributionReport contribution = storeContributionReportRepository
			.findByContributionReportId(reportId)
			.orElseThrow(() -> new AdminException(AdminErrorCode.REPORT_NOT_FOUND));
		assertContributionPending(contribution.getContributionReport(), contribution.getStatus());
		StoreReportType type = toStoreReportType(contribution.getRequestType());
		if (accept) {
			contributionReportProcessor.applyStoreReportApprove(contribution, type);
			contribution.approve();
			contribution.getContributionReport().approve();
		} else {
			contributionReportProcessor.cleanupStoreReportImages(contribution);
			contribution.reject();
			contribution.getContributionReport().reject();
		}
		reportNotificationService.notifyContributionReportResult(
			contribution.getMember(), contribution.getStore(), null, contribution.getContributionReport().getId(),
			contribution.getContributionReport().getId(),
			ReportKind.STORE_REPORT,
			accept ? ReportNotificationAction.APPROVED : ReportNotificationAction.REJECTED,
			subjectLabel(type));
	}

	private void reviewMenuReport(Long reportId, boolean accept) {
		MenuContributionReport contribution = menuContributionReportRepository.findByContributionReportId(reportId)
			.orElseThrow(() -> new AdminException(AdminErrorCode.REPORT_NOT_FOUND));
		assertContributionPending(contribution.getContributionReport(), contribution.getStatus());
		if (accept) {
			contributionReportProcessor.applyMenuReportApprove(contribution);
			contribution.approve();
			contribution.getContributionReport().approve();
		} else {
			contributionReportProcessor.cleanupMenuContributionImages(contribution);
			contribution.reject();
			contribution.getContributionReport().reject();
		}
		reportNotificationService.notifyContributionReportResult(
			contribution.getMember(), contribution.getStore(), null, contribution.getContributionReport().getId(),
			contribution.getContributionReport().getId(),
			ReportKind.STORE_REPORT,
			accept ? ReportNotificationAction.APPROVED : ReportNotificationAction.REJECTED,
			subjectLabel(toStoreReportType(contribution.getRequestType())));
	}

	private void reviewAbuseReport(Long reportId, boolean accept) {
		AbuseReport report = abuseReportRepository.findByIdWithMembers(reportId)
			.orElseThrow(() -> new AdminException(AdminErrorCode.REPORT_NOT_FOUND));
		if (report.getStatus() != AbuseReportStatus.PENDING) {
			throw new AdminException(AdminErrorCode.REPORT_ALREADY_REVIEWED);
		}
		if (accept) {
			report.accept();
		} else {
			report.reject();
		}
		reportNotificationService.notifyAbuseReportResult(report);
	}

	/** 대상 종류에 맞는 조치만 받는다 — 리뷰 조치를 빵로그 신고에 걸 수 없다. */
	private void applyTargetModeration(AbuseReport report, AbuseReportAction action, String reason) {
		if (action == AbuseReportAction.NONE) {
			return;
		}
		if (isRecordModerationAction(action)) {
			applyRecordModeration(report, action, reason);
			return;
		}
		if (isCommentModerationAction(action)) {
			applyCommentModeration(report, action, reason);
			return;
		}
		applyReviewModeration(report, action, reason);
	}

	private void applyRecordModeration(AbuseReport report, AbuseReportAction action, String reason) {
		if (report.getTargetType() != AbuseReportTargetType.RECORD) {
			throw new AdminException(AdminErrorCode.REPORT_TARGET_TYPE_MISMATCH);
		}
		BreadRecord record = breadRecordRepository.findByIdAndDeletedAtIsNull(report.getTargetId())
			.orElseThrow(() -> new AdminException(AdminErrorCode.REPORT_TARGET_NOT_FOUND));
		switch (action) {
			case HIDE_RECORD -> record.hide(reason, HIDDEN_BY_ADMIN);
			case RESTORE_RECORD -> record.restoreHidden();
			default -> throw new AdminException(AdminErrorCode.REPORT_INVALID_MODERATION_ACTION);
		}
	}

	private void applyCommentModeration(AbuseReport report, AbuseReportAction action, String reason) {
		if (report.getTargetType() != AbuseReportTargetType.COMMENT) {
			throw new AdminException(AdminErrorCode.REPORT_TARGET_TYPE_MISMATCH);
		}
		BreadRecordComment comment = commentRepository.findById(report.getTargetId())
			.orElseThrow(() -> new AdminException(AdminErrorCode.REPORT_TARGET_NOT_FOUND));
		switch (action) {
			case HIDE_COMMENT -> comment.hide(reason, HIDDEN_BY_ADMIN);
			case RESTORE_COMMENT -> comment.restoreHidden();
			case DELETE_COMMENT -> comment.delete(BreadRecordComment.DELETED_BY_ADMIN);
			default -> throw new AdminException(AdminErrorCode.REPORT_INVALID_MODERATION_ACTION);
		}
	}

	private void applyReviewModeration(AbuseReport report, AbuseReportAction action, String reason) {
		if (!isReviewModerationAction(action)) {
			throw new AdminException(AdminErrorCode.REPORT_INVALID_MODERATION_ACTION);
		}
		if (report.getTargetType() != AbuseReportTargetType.REVIEW) {
			throw new AdminException(AdminErrorCode.REPORT_TARGET_TYPE_MISMATCH);
		}

		Review review = reviewRepository.findByIdWithDetails(report.getTargetId())
			.orElseThrow(() -> new AdminException(AdminErrorCode.REPORT_TARGET_NOT_FOUND));
		switch (action) {
			case HIDE_REVIEW -> {
				if (review.getDeletedAt() != null) {
					throw new AdminException(AdminErrorCode.REPORT_TARGET_NOT_FOUND);
				}
				review.hide(reason, null);
			}
			case DELETE_REVIEW -> review.softDelete();
			case RESTORE_REVIEW -> {
				if (report.getModerationAction() == AbuseReportAction.DELETE_REVIEW) {
					review.restoreModeration();
				} else {
					review.restoreHidden();
				}
			}
			default -> throw new AdminException(AdminErrorCode.REPORT_INVALID_MODERATION_ACTION);
		}
	}

	private boolean isReviewModerationAction(AbuseReportAction action) {
		return action == AbuseReportAction.HIDE_REVIEW
			|| action == AbuseReportAction.DELETE_REVIEW
			|| action == AbuseReportAction.RESTORE_REVIEW;
	}

	private boolean isRecordModerationAction(AbuseReportAction action) {
		return action == AbuseReportAction.HIDE_RECORD || action == AbuseReportAction.RESTORE_RECORD;
	}

	private boolean isCommentModerationAction(AbuseReportAction action) {
		return action == AbuseReportAction.HIDE_COMMENT
			|| action == AbuseReportAction.RESTORE_COMMENT
			|| action == AbuseReportAction.DELETE_COMMENT;
	}

	/** 피조치자에게 알리는 조치 — 숨김 · 삭제. 복구는 알리지 않는다. */
	private boolean shouldNotifyModeratedMember(AbuseReportAction action) {
		return action == AbuseReportAction.HIDE_REVIEW
			|| action == AbuseReportAction.DELETE_REVIEW
			|| action == AbuseReportAction.HIDE_RECORD
			|| action == AbuseReportAction.HIDE_COMMENT
			|| action == AbuseReportAction.DELETE_COMMENT;
	}

	private void assertContributionPending(ContributionReport contributionReport, StoreReportStatus sourceStatus) {
		if (contributionReport.getStatus() != ContributionReportStatus.PENDING
			|| sourceStatus != StoreReportStatus.PENDING) {
			throw new AdminException(AdminErrorCode.REPORT_ALREADY_REVIEWED);
		}
	}

	private AbuseReportAction toModerationAction(String action) {
		if (action == null || action.isBlank()) {
			throw new AdminException(AdminErrorCode.REPORT_INVALID_MODERATION_ACTION);
		}
		try {
			AbuseReportAction parsed = AbuseReportAction.valueOf(action.trim().toUpperCase(Locale.ROOT));
			if (parsed == AbuseReportAction.NONE || isReviewModerationAction(parsed)
				|| isRecordModerationAction(parsed) || isCommentModerationAction(parsed)) {
				return parsed;
			}
		} catch (IllegalArgumentException e) {
			throw new AdminException(AdminErrorCode.REPORT_INVALID_MODERATION_ACTION);
		}
		throw new AdminException(AdminErrorCode.REPORT_INVALID_MODERATION_ACTION);
	}

	private String subjectLabel(StoreReportType type) {
		return switch (type) {
			case MOD_BAKERY_TIME -> "영업시간 수정";
			case MOD_BAKERY_ADDRESS -> "주소 수정";
			case MOD_BAKERY_IMG -> "가게 사진 수정";
			case MOD_BREAD -> "메뉴 수정";
		};
	}

	private StoreReportType toStoreReportType(String requestType) {
		try {
			return StoreReportType.valueOf(requestType);
		} catch (IllegalArgumentException | NullPointerException e) {
			throw new AdminException(AdminErrorCode.REPORT_INVALID_KIND);
		}
	}

	private boolean toAction(String action) {
		if ("ACCEPT".equalsIgnoreCase(action)) {
			return true;
		}
		if ("REJECT".equalsIgnoreCase(action)) {
			return false;
		}
		throw new AdminException(AdminErrorCode.REPORT_INVALID_ACTION);
	}
}
