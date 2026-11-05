package sweetgirl.croissant.abuse.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.abuse.code.AbuseReportErrorCode;
import sweetgirl.croissant.abuse.dto.request.AbuseReportCreateRequest;
import sweetgirl.croissant.abuse.dto.response.AbuseReportCreateResponse;
import sweetgirl.croissant.abuse.entity.AbuseReport;
import sweetgirl.croissant.abuse.entity.ModerationAction;
import sweetgirl.croissant.abuse.enums.AbuseReportAction;
import sweetgirl.croissant.abuse.enums.AbuseReportStatus;
import sweetgirl.croissant.abuse.enums.AbuseReportTargetType;
import sweetgirl.croissant.abuse.enums.ModerationTargetType;
import sweetgirl.croissant.abuse.exception.AbuseReportException;
import sweetgirl.croissant.abuse.repository.AbuseReportRepository;
import sweetgirl.croissant.abuse.repository.ModerationActionRepository;
import sweetgirl.croissant.alarm.service.ReportNotificationService;
import sweetgirl.croissant.breadlog.entity.BreadRecord;
import sweetgirl.croissant.breadlog.repository.BreadRecordRepository;
import sweetgirl.croissant.comment.entity.BreadRecordComment;
import sweetgirl.croissant.comment.repository.BreadRecordCommentRepository;
import sweetgirl.croissant.course.entity.Course;
import sweetgirl.croissant.course.repository.CourseRepository;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.repository.MemberRepository;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.notification.service.AdminReportNotificationPublisher;
import sweetgirl.croissant.review.entity.Review;
import sweetgirl.croissant.review.repository.ReviewRepository;

@Service
@RequiredArgsConstructor
@Transactional
public class AbuseReportCommandService {

	private static final List<AbuseReportStatus> DUPLICATE_BLOCKING_STATUSES = List.of(
		AbuseReportStatus.PENDING,
		AbuseReportStatus.ACCEPTED);

	private final MemberReader memberReader;
	private final MemberRepository memberRepository;
	private final ReviewRepository reviewRepository;
	private final CourseRepository courseRepository;
	private final AbuseReportRepository abuseReportRepository;
	private final AdminReportNotificationPublisher adminReportNotificationPublisher;
	private final BreadRecordRepository breadRecordRepository;
	private final BreadRecordCommentRepository commentRepository;
	private final ModerationActionRepository moderationActionRepository;
	private final ReportNotificationService reportNotificationService;

	/** 서로 다른 사람이 이만큼 신고하면 어드민이 보기 전에 임시로 숨긴다(빵로그 · 댓글). 프로필은 어드민 판단만. */
	public static final int AUTO_HIDE_REPORTER_THRESHOLD = 3;
	static final String AUTO_HIDE_REASON = "신고 3명 자동 숨김";
	public static final String HIDDEN_BY_SYSTEM = "SYSTEM";

	public AbuseReportCreateResponse createReport(Long memberId, AbuseReportCreateRequest request) {
		// 빵로그 · 댓글 신고는 3명이면 자동 숨김이라 익명(게스트) 표를 받지 않는다 — 기기마다 자동 발급되는 게스트 3개면 아무거나 숨길 수 있다
		if (request.targetType() == AbuseReportTargetType.RECORD
			|| request.targetType() == AbuseReportTargetType.COMMENT) {
			memberReader.requireLoggedIn(memberId);
		}
		Member reporter = memberReader.getMember(memberId);
		ReportTarget target = resolveTarget(request.targetType(), request.targetId());

		if (target.reportedMember().getId().equals(reporter.getId())) {
			throw new AbuseReportException(AbuseReportErrorCode.REPORT_SELF_TARGET_NOT_ALLOWED);
		}

		if (abuseReportRepository.existsByReporterIdAndTargetTypeAndTargetIdAndStatusIn(
			reporter.getId(),
			request.targetType(),
			request.targetId(),
			DUPLICATE_BLOCKING_STATUSES)) {
			throw new AbuseReportException(AbuseReportErrorCode.REPORT_ALREADY_EXISTS);
		}

		AbuseReport report = abuseReportRepository.save(request.toAbuseReport(reporter, target.reportedMember()));

		adminReportNotificationPublisher.publishAbuseReportCreated(report);
		autoHideIfReportedEnough(report, target);
		return AbuseReportCreateResponse.from(report);
	}

	/**
	 * 빵로그 · 댓글은 서로 다른 신고자가 {@value #AUTO_HIDE_REPORTER_THRESHOLD}명이면 바로 숨긴다(어드민 검토 뒤 복구 또는 확정).
	 * 작성자에겐 MODERATION_ACTION 알림이 가서 이의제기로 이어진다. 이미 숨겨져 있으면 다시 하지 않는다.
	 */
	private void autoHideIfReportedEnough(AbuseReport report, ReportTarget target) {
		if (target.record() == null && target.comment() == null) {
			return;
		}
		long reporters = abuseReportRepository.countDistinctReporters(
			report.getTargetType(), report.getTargetId(), DUPLICATE_BLOCKING_STATUSES);
		if (reporters < AUTO_HIDE_REPORTER_THRESHOLD) {
			return;
		}
		AbuseReportAction action;
		if (target.record() != null) {
			if (target.record().isHidden()) {
				return;
			}
			target.record().hide(AUTO_HIDE_REASON, HIDDEN_BY_SYSTEM);
			action = AbuseReportAction.HIDE_RECORD;
		} else {
			if (target.comment().isHidden()) {
				return;
			}
			target.comment().hide(AUTO_HIDE_REASON, HIDDEN_BY_SYSTEM);
			action = AbuseReportAction.HIDE_COMMENT;
		}
		report.applyModerationAction(action);
		ModerationAction moderationAction = moderationActionRepository.save(ModerationAction.builder()
			.report(report)
			.targetType(ModerationTargetType.fromAbuseReportTargetType(report.getTargetType()))
			.targetId(report.getTargetId())
			.member(target.reportedMember())
			.action(action)
			.reason(AUTO_HIDE_REASON)
			.build());
		reportNotificationService.notifyModerationAction(moderationAction);
	}

	private ReportTarget resolveTarget(AbuseReportTargetType targetType, Long targetId) {
		return switch (targetType) {
			case REVIEW -> resolveReviewTarget(targetId);
			case MEMBER_PROFILE -> resolveMemberProfileTarget(targetId);
			case COURSE -> resolveCourseTarget(targetId);
			case RECORD -> resolveRecordTarget(targetId);
			case COMMENT -> resolveCommentTarget(targetId);
		};
	}

	private ReportTarget resolveRecordTarget(Long recordId) {
		BreadRecord record = breadRecordRepository.findByIdAndDeletedAtIsNull(recordId)
			.filter(BreadRecord::isPublic)
			.orElseThrow(() -> new AbuseReportException(AbuseReportErrorCode.REPORT_TARGET_NOT_FOUND));
		return new ReportTarget(record.getMember(), record, null);
	}

	private ReportTarget resolveCommentTarget(Long commentId) {
		BreadRecordComment comment = commentRepository.findActiveByIdWithRecord(commentId)
			.orElseThrow(() -> new AbuseReportException(AbuseReportErrorCode.REPORT_TARGET_NOT_FOUND));
		return new ReportTarget(comment.getMember(), null, comment);
	}

	private ReportTarget resolveCourseTarget(Long courseId) {
		Course course = courseRepository.findByIdAndDeletedAtIsNull(courseId)
			.orElseThrow(() -> new AbuseReportException(AbuseReportErrorCode.REPORT_TARGET_NOT_FOUND));
		if (course.getMember() == null) {
			// 작성자가 없는 큐레이션 코스는 신고 대상이 아니다
			throw new AbuseReportException(AbuseReportErrorCode.REPORT_TARGET_NOT_FOUND);
		}
		return new ReportTarget(course.getMember(), null, null);
	}

	private ReportTarget resolveReviewTarget(Long reviewId) {
		Review review = reviewRepository.findActiveByIdWithDetails(reviewId)
			.orElseThrow(() -> new AbuseReportException(AbuseReportErrorCode.REPORT_TARGET_NOT_FOUND));
		return new ReportTarget(review.getMember(), null, null);
	}

	private ReportTarget resolveMemberProfileTarget(Long memberId) {
		Member member = memberRepository.findByIdAndDeletedAtIsNull(memberId)
			.orElseThrow(() -> new AbuseReportException(AbuseReportErrorCode.REPORT_TARGET_NOT_FOUND));
		return new ReportTarget(member, null, null);
	}

	/** 자동 숨김 대상이 될 수 있는 빵로그 · 댓글은 엔티티를 같이 들고 온다. */
	private record ReportTarget(Member reportedMember, BreadRecord record, BreadRecordComment comment) {
	}
}
