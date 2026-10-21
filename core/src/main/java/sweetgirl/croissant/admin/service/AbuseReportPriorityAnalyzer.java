package sweetgirl.croissant.admin.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.abuse.dto.projection.AbuseReportTargetCountProjection;
import sweetgirl.croissant.abuse.entity.AbuseReport;
import sweetgirl.croissant.abuse.enums.AbuseReportReasonType;
import sweetgirl.croissant.abuse.enums.AbuseReportStatus;
import sweetgirl.croissant.abuse.enums.AbuseReportTargetType;
import sweetgirl.croissant.abuse.repository.AbuseReportRepository;
import sweetgirl.croissant.admin.dto.response.AdminAbuseReportStatsResponse;
import sweetgirl.croissant.admin.dto.response.AdminHighPriorityReportResponse;

// 사용자 신고 우선순위 큐 집계·통계 전담. 조회 트랜잭션 내에서만 사용된다.
@Component
@RequiredArgsConstructor
public class AbuseReportPriorityAnalyzer {

	private static final int PRIORITY_LOOKBACK_HOURS = 24;
	private static final int REVIEW_HIDE_CANDIDATE_REPORTER_THRESHOLD = 3;
	private static final int SENSITIVE_REASON_PRIORITY_REPORTER_THRESHOLD = 2;
	private static final int MEMBER_REVIEW_CANDIDATE_TARGET_THRESHOLD = 3;
	private static final List<AbuseReportReasonType> SENSITIVE_REASON_TYPES = List.of(
		AbuseReportReasonType.PRIVACY,
		AbuseReportReasonType.HATE,
		AbuseReportReasonType.SEXUAL);
	private static final Comparator<AdminHighPriorityReportResponse> HIGH_PRIORITY_COMPARATOR = Comparator
		.comparingInt(AdminHighPriorityReportResponse::priorityScore).reversed()
		.thenComparing(Comparator.comparingLong(
			AdminHighPriorityReportResponse::sensitiveReasonReportCount).reversed())
		.thenComparing(Comparator.comparingLong(
			AdminHighPriorityReportResponse::reviewReportCount24h).reversed())
		.thenComparing(Comparator.comparingLong(
			AdminHighPriorityReportResponse::memberReviewReportCount24h).reversed())
		.thenComparing(AdminHighPriorityReportResponse::createdAt, Comparator.nullsLast(Comparator.reverseOrder()))
		.thenComparing(AdminHighPriorityReportResponse::reportId, Comparator.reverseOrder());

	private final AbuseReportRepository abuseReportRepository;

	public AdminAbuseReportStatsResponse getAbuseReportStats() {
		LocalDateTime since = LocalDateTime.now().minusHours(PRIORITY_LOOKBACK_HOURS);
		PriorityAggregation aggregation = aggregatePriorityMetrics(since, AbuseReportStatus.PENDING);

		return new AdminAbuseReportStatsResponse(
			abuseReportRepository.count(),
			abuseReportRepository.countByStatus(AbuseReportStatus.PENDING),
			abuseReportRepository.countByStatus(AbuseReportStatus.ACCEPTED),
			abuseReportRepository.countByStatus(AbuseReportStatus.REJECTED),
			abuseReportRepository.countByCreatedAtGreaterThanEqual(since),
			aggregation.reviewHideCandidateCount(),
			aggregation.sensitiveReasonPriorityCount(),
			aggregation.memberReviewCandidateCount(),
			PRIORITY_LOOKBACK_HOURS,
			REVIEW_HIDE_CANDIDATE_REPORTER_THRESHOLD,
			SENSITIVE_REASON_PRIORITY_REPORTER_THRESHOLD,
			MEMBER_REVIEW_CANDIDATE_TARGET_THRESHOLD,
			SENSITIVE_REASON_TYPES.stream()
				.map(Enum::name)
				.toList());
	}

	public Page<AdminHighPriorityReportResponse> getHighPriorityReports(AbuseReportStatus status, int page, int size) {
		LocalDateTime since = LocalDateTime.now().minusHours(PRIORITY_LOOKBACK_HOURS);
		PriorityAggregation aggregation = aggregatePriorityMetrics(since, status);
		List<AbuseReport> reports = status != null
			? abuseReportRepository.findByStatus(status)
			: abuseReportRepository.findAllWithMembers();

		List<AdminHighPriorityReportResponse> highPriorityReports = reports.stream()
			.map(report -> toHighPriorityReportResponse(report, aggregation))
			.filter(AdminHighPriorityReportResponse::highPriority)
			.sorted(HIGH_PRIORITY_COMPARATOR)
			.toList();

		PageRequest pageRequest = PageRequest.of(page, size);
		int start = Math.toIntExact(pageRequest.getOffset());
		if (start >= highPriorityReports.size()) {
			return new PageImpl<>(List.of(), pageRequest, highPriorityReports.size());
		}
		int end = Math.min(start + pageRequest.getPageSize(), highPriorityReports.size());
		return new PageImpl<>(highPriorityReports.subList(start, end), pageRequest, highPriorityReports.size());
	}

	private PriorityAggregation aggregatePriorityMetrics(LocalDateTime since, AbuseReportStatus status) {
		Map<TargetKey, Long> reviewReportCounts24h = toTargetCountMap(
			abuseReportRepository.findTargetReporterCountsSince(AbuseReportTargetType.REVIEW, since, status));
		Map<TargetKey, Long> sensitiveReasonReportCounts = toTargetCountMap(
			abuseReportRepository.findSensitiveReasonReporterCounts(SENSITIVE_REASON_TYPES, status));
		Map<Long, Long> memberReviewCounts24h = abuseReportRepository
			.findMemberReviewTargetCountsSince(AbuseReportTargetType.REVIEW, since, status).stream()
			.collect(Collectors.toMap(
				projection -> projection.getReportedMemberId(),
				projection -> nullToZero(projection.getReviewTargetCount())));

		return new PriorityAggregation(
			reviewReportCounts24h,
			sensitiveReasonReportCounts,
			memberReviewCounts24h);
	}

	private Map<TargetKey, Long> toTargetCountMap(
			List<? extends AbuseReportTargetCountProjection> counts) {
		Map<TargetKey, Long> result = new HashMap<>();
		for (var count : counts) {
			result.put(new TargetKey(count.getTargetType(), count.getTargetId()), nullToZero(count.getReporterCount()));
		}
		return result;
	}

	private AdminHighPriorityReportResponse toHighPriorityReportResponse(
			AbuseReport report,
			PriorityAggregation aggregation) {
		TargetKey targetKey = new TargetKey(report.getTargetType(), report.getTargetId());
		long reviewReportCount24h = aggregation.reviewReportCounts24h().getOrDefault(targetKey, 0L);
		long sensitiveReasonReportCount = aggregation.sensitiveReasonReportCounts().getOrDefault(targetKey, 0L);
		long memberReviewReportCount24h = aggregation.memberReviewCounts24h()
			.getOrDefault(report.getReportedMember().getId(), 0L);
		boolean temporaryHideCandidate = report.getTargetType() == AbuseReportTargetType.REVIEW
			&& reviewReportCount24h >= REVIEW_HIDE_CANDIDATE_REPORTER_THRESHOLD;
		boolean sensitiveReasonPriority = sensitiveReasonReportCount >= SENSITIVE_REASON_PRIORITY_REPORTER_THRESHOLD;
		boolean memberReviewCandidate = memberReviewReportCount24h >= MEMBER_REVIEW_CANDIDATE_TARGET_THRESHOLD;
		List<String> priorityReasons = priorityReasons(
			temporaryHideCandidate,
			sensitiveReasonPriority,
			memberReviewCandidate);

		return AdminHighPriorityReportResponse.of(
			report,
			reviewReportCount24h,
			sensitiveReasonReportCount,
			memberReviewReportCount24h,
			temporaryHideCandidate,
			sensitiveReasonPriority,
			memberReviewCandidate,
			priorityScore(temporaryHideCandidate, sensitiveReasonPriority, memberReviewCandidate),
			priorityReasons);
	}

	private List<String> priorityReasons(
			boolean temporaryHideCandidate,
			boolean sensitiveReasonPriority,
			boolean memberReviewCandidate) {
		List<String> reasons = new ArrayList<>();
		if (sensitiveReasonPriority) {
			reasons.add("SENSITIVE_REASON_PRIORITY");
		}
		if (temporaryHideCandidate) {
			reasons.add("TEMPORARY_HIDE_CANDIDATE");
		}
		if (memberReviewCandidate) {
			reasons.add("MEMBER_REVIEW_CANDIDATE");
		}
		return reasons;
	}

	private int priorityScore(
			boolean temporaryHideCandidate,
			boolean sensitiveReasonPriority,
			boolean memberReviewCandidate) {
		int score = 0;
		if (sensitiveReasonPriority) {
			score += 100;
		}
		if (temporaryHideCandidate) {
			score += 50;
		}
		if (memberReviewCandidate) {
			score += 25;
		}
		return score;
	}

	private long nullToZero(Long value) {
		return value == null ? 0L : value;
	}

	private record TargetKey(AbuseReportTargetType targetType, Long targetId) {
	}

	private record PriorityAggregation(
			Map<TargetKey, Long> reviewReportCounts24h,
			Map<TargetKey, Long> sensitiveReasonReportCounts,
			Map<Long, Long> memberReviewCounts24h) {

		long reviewHideCandidateCount() {
			return reviewReportCounts24h.values().stream()
				.filter(count -> count >= REVIEW_HIDE_CANDIDATE_REPORTER_THRESHOLD)
				.count();
		}

		long sensitiveReasonPriorityCount() {
			return sensitiveReasonReportCounts.values().stream()
				.filter(count -> count >= SENSITIVE_REASON_PRIORITY_REPORTER_THRESHOLD)
				.count();
		}

		long memberReviewCandidateCount() {
			return memberReviewCounts24h.values().stream()
				.filter(count -> count >= MEMBER_REVIEW_CANDIDATE_TARGET_THRESHOLD)
				.count();
		}
	}
}
