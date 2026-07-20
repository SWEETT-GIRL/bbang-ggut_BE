package sweetgirl.croissant.abuse.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.abuse.code.AbuseReportErrorCode;
import sweetgirl.croissant.abuse.dto.request.AbuseReportCreateRequest;
import sweetgirl.croissant.abuse.dto.response.AbuseReportCreateResponse;
import sweetgirl.croissant.abuse.entity.AbuseReport;
import sweetgirl.croissant.abuse.enums.AbuseReportStatus;
import sweetgirl.croissant.abuse.enums.AbuseReportTargetType;
import sweetgirl.croissant.abuse.exception.AbuseReportException;
import sweetgirl.croissant.abuse.repository.AbuseReportRepository;
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

	public AbuseReportCreateResponse createReport(Long memberId, AbuseReportCreateRequest request) {
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
		return AbuseReportCreateResponse.from(report);
	}

	private ReportTarget resolveTarget(AbuseReportTargetType targetType, Long targetId) {
		return switch (targetType) {
			case REVIEW -> resolveReviewTarget(targetId);
			case MEMBER_PROFILE -> resolveMemberProfileTarget(targetId);
			case COURSE -> resolveCourseTarget(targetId);
		};
	}

	private ReportTarget resolveCourseTarget(Long courseId) {
		Course course = courseRepository.findByIdAndDeletedAtIsNull(courseId)
			.orElseThrow(() -> new AbuseReportException(AbuseReportErrorCode.REPORT_TARGET_NOT_FOUND));
		if (course.getMember() == null) {
			// 작성자가 없는 큐레이션 코스는 신고 대상이 아니다
			throw new AbuseReportException(AbuseReportErrorCode.REPORT_TARGET_NOT_FOUND);
		}
		return new ReportTarget(course.getMember());
	}

	private ReportTarget resolveReviewTarget(Long reviewId) {
		Review review = reviewRepository.findActiveByIdWithDetails(reviewId)
			.orElseThrow(() -> new AbuseReportException(AbuseReportErrorCode.REPORT_TARGET_NOT_FOUND));
		return new ReportTarget(review.getMember());
	}

	private ReportTarget resolveMemberProfileTarget(Long memberId) {
		Member member = memberRepository.findByIdAndDeletedAtIsNull(memberId)
			.orElseThrow(() -> new AbuseReportException(AbuseReportErrorCode.REPORT_TARGET_NOT_FOUND));
		return new ReportTarget(member);
	}

	private record ReportTarget(Member reportedMember) {
	}
}
