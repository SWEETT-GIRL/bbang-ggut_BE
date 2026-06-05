package sweetgirl.croissant.report.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.repository.MemberRepository;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.report.code.UserReportErrorCode;
import sweetgirl.croissant.report.dto.request.UserReportCreateRequest;
import sweetgirl.croissant.report.dto.response.UserReportCreateResponse;
import sweetgirl.croissant.report.entity.UserReport;
import sweetgirl.croissant.report.enums.ReportTargetType;
import sweetgirl.croissant.report.enums.UserReportStatus;
import sweetgirl.croissant.report.exception.UserReportException;
import sweetgirl.croissant.report.repository.UserReportRepository;
import sweetgirl.croissant.review.entity.Review;
import sweetgirl.croissant.review.repository.ReviewRepository;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserReportService {

	private static final List<UserReportStatus> DUPLICATE_BLOCKING_STATUSES = List.of(
		UserReportStatus.PENDING,
		UserReportStatus.ACCEPTED);

	private final MemberReader memberReader;
	private final MemberRepository memberRepository;
	private final ReviewRepository reviewRepository;
	private final UserReportRepository userReportRepository;

	@Transactional
	public UserReportCreateResponse createReport(Long memberId, UserReportCreateRequest request) {
		Member reporter = memberReader.getMember(memberId);
		ReportTarget target = resolveTarget(request.targetType(), request.targetId());

		if (target.reportedMember().getId().equals(reporter.getId())) {
			throw new UserReportException(UserReportErrorCode.REPORT_SELF_TARGET_NOT_ALLOWED);
		}

		if (userReportRepository.existsByReporterIdAndTargetTypeAndTargetIdAndStatusIn(
			reporter.getId(),
			request.targetType(),
			request.targetId(),
			DUPLICATE_BLOCKING_STATUSES)) {
			throw new UserReportException(UserReportErrorCode.REPORT_ALREADY_EXISTS);
		}

		UserReport report = userReportRepository.save(request.toUserReport(reporter, target.reportedMember()));

		return UserReportCreateResponse.from(report);
	}

	private ReportTarget resolveTarget(ReportTargetType targetType, Long targetId) {
		return switch (targetType) {
			case REVIEW -> resolveReviewTarget(targetId);
			case MEMBER_PROFILE -> resolveMemberProfileTarget(targetId);
		};
	}

	private ReportTarget resolveReviewTarget(Long reviewId) {
		Review review = reviewRepository.findActiveByIdWithDetails(reviewId)
			.orElseThrow(() -> new UserReportException(UserReportErrorCode.REPORT_TARGET_NOT_FOUND));
		return new ReportTarget(review.getMember());
	}

	private ReportTarget resolveMemberProfileTarget(Long memberId) {
		Member member = memberRepository.findByIdAndDeletedAtIsNull(memberId)
			.orElseThrow(() -> new UserReportException(UserReportErrorCode.REPORT_TARGET_NOT_FOUND));
		return new ReportTarget(member);
	}

	private record ReportTarget(Member reportedMember) {
	}
}
