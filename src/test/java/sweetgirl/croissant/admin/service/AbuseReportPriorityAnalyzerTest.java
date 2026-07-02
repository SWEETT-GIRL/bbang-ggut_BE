package sweetgirl.croissant.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;

import sweetgirl.croissant.abuse.dto.projection.AbuseReportTargetCountProjection;
import sweetgirl.croissant.abuse.entity.AbuseReport;
import sweetgirl.croissant.abuse.enums.AbuseReportReasonType;
import sweetgirl.croissant.abuse.enums.AbuseReportStatus;
import sweetgirl.croissant.abuse.enums.AbuseReportTargetType;
import sweetgirl.croissant.abuse.repository.AbuseReportRepository;
import sweetgirl.croissant.admin.dto.response.AdminAbuseReportStatsResponse;
import sweetgirl.croissant.admin.dto.response.AdminHighPriorityReportResponse;
import sweetgirl.croissant.member.entity.Member;

@ExtendWith(MockitoExtension.class)
class AbuseReportPriorityAnalyzerTest {

	@Mock
	AbuseReportRepository abuseReportRepository;

	@InjectMocks
	AbuseReportPriorityAnalyzer priorityAnalyzer;

	@Test
	@DisplayName("민감 사유 신고자 수가 임계값 이상이면 high-priority로 분류되고 점수·사유가 반영된다")
	void getHighPriorityReports_sensitiveReason_marksHighPriority() {
		AbuseReport sensitiveReport = reviewReport(1L, 10L, 100L);
		AbuseReport plainReport = reviewReport(2L, 20L, 200L);
		given(abuseReportRepository.findByStatus(AbuseReportStatus.PENDING))
			.willReturn(List.of(sensitiveReport, plainReport));
		// 24시간 내 리뷰 신고자 수 없음
		given(abuseReportRepository.findTargetReporterCountsSince(
			eq(AbuseReportTargetType.REVIEW), any(), eq(AbuseReportStatus.PENDING)))
			.willReturn(List.of());
		// review 10 은 민감 사유 신고자 2명 → SENSITIVE_REASON_PRIORITY
		AbuseReportTargetCountProjection sensitiveCount = targetCount(AbuseReportTargetType.REVIEW, 10L, 2L);
		given(abuseReportRepository.findSensitiveReasonReporterCounts(any(), eq(AbuseReportStatus.PENDING)))
			.willReturn(List.of(sensitiveCount));
		given(abuseReportRepository.findMemberReviewTargetCountsSince(
			eq(AbuseReportTargetType.REVIEW), any(), eq(AbuseReportStatus.PENDING)))
			.willReturn(List.of());

		Page<AdminHighPriorityReportResponse> result = priorityAnalyzer
			.getHighPriorityReports(AbuseReportStatus.PENDING, 0, 20);

		assertThat(result.getTotalElements()).isEqualTo(1);
		AdminHighPriorityReportResponse only = result.getContent().get(0);
		assertThat(only.reportId()).isEqualTo(1L);
		assertThat(only.highPriority()).isTrue();
		assertThat(only.sensitiveReasonPriority()).isTrue();
		assertThat(only.priorityScore()).isEqualTo(100);
		assertThat(only.priorityReasons()).containsExactly("SENSITIVE_REASON_PRIORITY");
	}

	@Test
	@DisplayName("우선순위 신호가 없으면 결과에서 제외된다")
	void getHighPriorityReports_noSignal_isFilteredOut() {
		AbuseReport plainReport = reviewReport(3L, 30L, 300L);
		given(abuseReportRepository.findByStatus(AbuseReportStatus.PENDING))
			.willReturn(List.of(plainReport));
		given(abuseReportRepository.findTargetReporterCountsSince(any(), any(), any()))
			.willReturn(List.of());
		given(abuseReportRepository.findSensitiveReasonReporterCounts(any(), any()))
			.willReturn(List.of());
		given(abuseReportRepository.findMemberReviewTargetCountsSince(any(), any(), any()))
			.willReturn(List.of());

		Page<AdminHighPriorityReportResponse> result = priorityAnalyzer
			.getHighPriorityReports(AbuseReportStatus.PENDING, 0, 20);

		assertThat(result.getTotalElements()).isZero();
	}

	@Test
	@DisplayName("통계 조회는 상태별 카운트와 후보 집계를 반환한다")
	void getAbuseReportStats_returnsCountsAndCandidates() {
		given(abuseReportRepository.count()).willReturn(10L);
		given(abuseReportRepository.countByStatus(AbuseReportStatus.PENDING)).willReturn(4L);
		given(abuseReportRepository.countByStatus(AbuseReportStatus.ACCEPTED)).willReturn(3L);
		given(abuseReportRepository.countByStatus(AbuseReportStatus.REJECTED)).willReturn(3L);
		given(abuseReportRepository.countByCreatedAtGreaterThanEqual(any())).willReturn(5L);
		AbuseReportTargetCountProjection reviewHideCount = targetCount(AbuseReportTargetType.REVIEW, 10L, 3L);
		AbuseReportTargetCountProjection sensitiveCount = targetCount(AbuseReportTargetType.REVIEW, 11L, 2L);
		given(abuseReportRepository.findTargetReporterCountsSince(
			eq(AbuseReportTargetType.REVIEW), any(), eq(AbuseReportStatus.PENDING)))
			.willReturn(List.of(reviewHideCount));
		given(abuseReportRepository.findSensitiveReasonReporterCounts(any(), eq(AbuseReportStatus.PENDING)))
			.willReturn(List.of(sensitiveCount));
		given(abuseReportRepository.findMemberReviewTargetCountsSince(
			eq(AbuseReportTargetType.REVIEW), any(), eq(AbuseReportStatus.PENDING)))
			.willReturn(List.of());

		AdminAbuseReportStatsResponse stats = priorityAnalyzer.getAbuseReportStats();

		assertThat(stats.totalReportCount()).isEqualTo(10L);
		assertThat(stats.pendingReportCount()).isEqualTo(4L);
		// review 10 신고자 3명 → 임시숨김 후보 1건
		assertThat(stats.pendingReviewHideCandidateCount()).isEqualTo(1L);
		// review 11 민감 사유 신고자 2명 → 민감 사유 우선 1건
		assertThat(stats.pendingSensitiveReasonPriorityCount()).isEqualTo(1L);
		assertThat(stats.sensitiveReasonTypes()).containsExactly("PRIVACY", "HATE", "SEXUAL");
	}

	private AbuseReport reviewReport(Long id, Long targetId, Long reportedMemberId) {
		Member reporter = member(999L);
		Member reportedMember = member(reportedMemberId);
		AbuseReport report = mock(AbuseReport.class);
		given(report.getId()).willReturn(id);
		given(report.getTargetType()).willReturn(AbuseReportTargetType.REVIEW);
		given(report.getTargetId()).willReturn(targetId);
		given(report.getReporter()).willReturn(reporter);
		given(report.getReportedMember()).willReturn(reportedMember);
		given(report.getReasonType()).willReturn(AbuseReportReasonType.PRIVACY);
		given(report.getReasonDetail()).willReturn("사유");
		given(report.getStatus()).willReturn(AbuseReportStatus.PENDING);
		return report;
	}

	private AbuseReportTargetCountProjection targetCount(AbuseReportTargetType type, Long targetId, Long count) {
		AbuseReportTargetCountProjection projection = mock(AbuseReportTargetCountProjection.class);
		given(projection.getTargetType()).willReturn(type);
		given(projection.getTargetId()).willReturn(targetId);
		given(projection.getReporterCount()).willReturn(count);
		return projection;
	}

	private Member member(Long id) {
		Member member = mock(Member.class);
		given(member.getId()).willReturn(id);
		return member;
	}
}
