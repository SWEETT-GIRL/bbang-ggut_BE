package sweetgirl.croissant.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.locationtech.jts.geom.Point;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;

import sweetgirl.croissant.abuse.dto.projection.AbuseReportMemberCountProjection;
import sweetgirl.croissant.abuse.dto.projection.AbuseReportTargetCountProjection;
import sweetgirl.croissant.abuse.entity.AbuseReport;
import sweetgirl.croissant.abuse.entity.ModerationAction;
import sweetgirl.croissant.abuse.enums.AbuseReportAction;
import sweetgirl.croissant.abuse.enums.AbuseReportReasonType;
import sweetgirl.croissant.abuse.enums.AbuseReportStatus;
import sweetgirl.croissant.abuse.enums.AbuseReportTargetType;
import sweetgirl.croissant.abuse.enums.ModerationTargetType;
import sweetgirl.croissant.abuse.repository.AbuseReportRepository;
import sweetgirl.croissant.abuse.repository.ModerationActionRepository;
import sweetgirl.croissant.admin.code.AdminErrorCode;
import sweetgirl.croissant.admin.dto.request.AdminModerationActionRequest;
import sweetgirl.croissant.admin.dto.request.AdminReportReviewRequest;
import sweetgirl.croissant.admin.dto.response.AdminAbuseReportDetailResponse;
import sweetgirl.croissant.admin.dto.response.AdminAbuseReportStatsResponse;
import sweetgirl.croissant.admin.dto.response.AdminHighPriorityReportResponse;
import sweetgirl.croissant.admin.dto.response.AdminModerationActionResponse;
import sweetgirl.croissant.admin.dto.response.AdminReportDetailResponse;
import sweetgirl.croissant.admin.exception.AdminException;
import sweetgirl.croissant.alarm.enums.ReportNotificationAction;
import sweetgirl.croissant.alarm.service.ReportNotificationService;
import sweetgirl.croissant.common.enums.MimeType;
import sweetgirl.croissant.common.enums.ReportKind;
import sweetgirl.croissant.contribution.entity.ContributionReport;
import sweetgirl.croissant.contribution.entity.MenuContributionReport;
import sweetgirl.croissant.contribution.entity.StoreContributionReport;
import sweetgirl.croissant.contribution.enums.ContributionReportType;
import sweetgirl.croissant.contribution.enums.ContributionTargetType;
import sweetgirl.croissant.contribution.repository.ContributionReportRepository;
import sweetgirl.croissant.contribution.repository.MenuContributionReportRepository;
import sweetgirl.croissant.contribution.repository.StoreContributionReportRepository;
import sweetgirl.croissant.external.aws.S3Service;
import sweetgirl.croissant.external.opensearch.event.StoreAddressChangedEvent;
import sweetgirl.croissant.external.opensearch.event.StoreMenusChangedEvent;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.menu.entity.Menu;
import sweetgirl.croissant.menu.entity.MenuAddReport;
import sweetgirl.croissant.menu.entity.MenuAddReportImageFile;
import sweetgirl.croissant.menu.entity.MenuCategory;
import sweetgirl.croissant.menu.entity.MenuImageFile;
import sweetgirl.croissant.menu.repository.MenuAddReportImageFileRepository;
import sweetgirl.croissant.menu.repository.MenuAddReportRepository;
import sweetgirl.croissant.menu.repository.MenuImageFileRepository;
import sweetgirl.croissant.menu.repository.MenuRepository;
import sweetgirl.croissant.review.entity.Review;
import sweetgirl.croissant.review.entity.ReviewImageFile;
import sweetgirl.croissant.review.repository.ReviewImageFileRepository;
import sweetgirl.croissant.review.repository.ReviewRepository;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.entity.StoreAddReport;
import sweetgirl.croissant.store.entity.StoreBusinessHour;
import sweetgirl.croissant.store.entity.StoreImageFile;
import sweetgirl.croissant.store.entity.StoreReport;
import sweetgirl.croissant.store.entity.StoreReportImageFile;
import sweetgirl.croissant.store.enums.StoreReportStatus;
import sweetgirl.croissant.store.enums.StoreReportType;
import sweetgirl.croissant.store.repository.StoreAddReportRepository;
import sweetgirl.croissant.store.repository.StoreBusinessHourRepository;
import sweetgirl.croissant.store.repository.StoreImageFileRepository;
import sweetgirl.croissant.store.repository.StoreReportImageFileRepository;
import sweetgirl.croissant.store.repository.StoreReportRepository;
import sweetgirl.croissant.store.repository.StoreRepository;

@ExtendWith(MockitoExtension.class)
class AdminReportServiceTest {

	@Mock
	StoreReportRepository storeReportRepository;
	@Mock
	StoreAddReportRepository storeAddReportRepository;
	@Mock
	StoreRepository storeRepository;
	@Mock
	MenuAddReportRepository menuAddReportRepository;
	@Mock
	ContributionReportRepository contributionReportRepository;
	@Mock
	StoreContributionReportRepository storeContributionReportRepository;
	@Mock
	MenuContributionReportRepository menuContributionReportRepository;
	@Mock
	AbuseReportRepository abuseReportRepository;
	@Mock
	ModerationActionRepository moderationActionRepository;
	@Mock
	StoreReportImageFileRepository storeReportImageFileRepository;
	@Mock
	MenuAddReportImageFileRepository menuAddReportImageFileRepository;
	@Mock
	StoreImageFileRepository storeImageFileRepository;
	@Mock
	StoreBusinessHourRepository storeBusinessHourRepository;
	@Mock
	MenuRepository menuRepository;
	@Mock
	MenuImageFileRepository menuImageFileRepository;
	@Mock
	ReviewRepository reviewRepository;
	@Mock
	ReviewImageFileRepository reviewImageFileRepository;
	@Mock
	S3Service s3Service;
	@Mock
	ReportNotificationService reportNotificationService;
	@Mock
	ApplicationEventPublisher eventPublisher;

	AdminReportService adminReportService;

	@BeforeEach
	void setUp() {
		adminReportService = new AdminReportService(
			storeReportRepository,
			storeAddReportRepository,
			storeRepository,
			menuAddReportRepository,
			contributionReportRepository,
			storeContributionReportRepository,
			menuContributionReportRepository,
			abuseReportRepository,
			moderationActionRepository,
			storeReportImageFileRepository,
			menuAddReportImageFileRepository,
			storeImageFileRepository,
			storeBusinessHourRepository,
			menuRepository,
			menuImageFileRepository,
			reviewRepository,
			reviewImageFileRepository,
			s3Service,
			new ObjectMapper(),
			reportNotificationService,
			eventPublisher);
	}

	@Test
	@DisplayName("AbuseReport 상세 조회: 리뷰 대상 원문과 이미지를 반환한다")
	void getReportDetail_abuseReportReview_returnsReviewTarget() {
		Member reporter = mock(Member.class);
		given(reporter.getId()).willReturn(10L);
		Member reportedMember = mock(Member.class);
		given(reportedMember.getId()).willReturn(20L);
		AbuseReport report = mock(AbuseReport.class);
		given(report.getId()).willReturn(1L);
		given(report.getTargetType()).willReturn(AbuseReportTargetType.REVIEW);
		given(report.getTargetId()).willReturn(123L);
		given(report.getReasonType()).willReturn(AbuseReportReasonType.ABUSE);
		given(report.getReasonDetail()).willReturn("욕설");
		given(report.getStatus()).willReturn(AbuseReportStatus.PENDING);
		given(report.getModerationAction()).willReturn(AbuseReportAction.NONE);
		given(report.getReporter()).willReturn(reporter);
		given(report.getReportedMember()).willReturn(reportedMember);
		given(abuseReportRepository.findByIdWithMembers(1L)).willReturn(Optional.of(report));

		Store store = mock(Store.class);
		given(store.getId()).willReturn(2L);
		given(store.getName()).willReturn("빵집");
		Menu menu = mock(Menu.class);
		given(menu.getId()).willReturn(3L);
		given(menu.getName()).willReturn("소금빵");
		given(menu.getStore()).willReturn(store);
		Review review = mock(Review.class);
		given(review.getId()).willReturn(123L);
		given(review.getMenu()).willReturn(menu);
		given(review.getMember()).willReturn(reportedMember);
		given(review.getContent()).willReturn("문제된 리뷰");
		given(reviewRepository.findByIdWithDetails(123L)).willReturn(Optional.of(review));

		ReviewImageFile image = mock(ReviewImageFile.class);
		given(image.getAwsKey()).willReturn("reviews/members/20/images/a.jpg");
		given(reviewImageFileRepository.findByReviewIdOrderByDisplayOrderAscIdAsc(123L))
			.willReturn(List.of(image));

		AdminReportDetailResponse response = adminReportService.getReportDetail(1L, "ABUSE_REPORT");

		assertThat(response.reportId()).isEqualTo(1L);
		assertThat(response.targetType()).isEqualTo("REVIEW");
		assertThat(response.reporterId()).isEqualTo(10L);
		assertThat(response.reportedMemberId()).isEqualTo(20L);
		assertThat(response.target()).isInstanceOf(AdminAbuseReportDetailResponse.ReviewTarget.class);
		var target = (AdminAbuseReportDetailResponse.ReviewTarget)response.target();
		assertThat(target.reviewId()).isEqualTo(123L);
		assertThat(target.menuName()).isEqualTo("소금빵");
		assertThat(target.storeName()).isEqualTo("빵집");
		assertThat(target.content()).isEqualTo("문제된 리뷰");
		assertThat(target.images()).containsExactly("reviews/members/20/images/a.jpg");
	}

	@Test
	@DisplayName("AbuseReport 상세 조회: 프로필 대상이면 신고된 회원 프로필 정보를 반환한다")
	void getReportDetail_abuseReportMemberProfile_returnsMemberTarget() {
		Member reporter = mock(Member.class);
		given(reporter.getId()).willReturn(10L);
		Member reportedMember = mock(Member.class);
		given(reportedMember.getId()).willReturn(20L);
		given(reportedMember.getNickname()).willReturn("부적절닉네임");
		AbuseReport report = mock(AbuseReport.class);
		given(report.getId()).willReturn(2L);
		given(report.getTargetType()).willReturn(AbuseReportTargetType.MEMBER_PROFILE);
		given(report.getTargetId()).willReturn(20L);
		given(report.getReasonType()).willReturn(AbuseReportReasonType.ETC);
		given(report.getStatus()).willReturn(AbuseReportStatus.PENDING);
		given(report.getModerationAction()).willReturn(AbuseReportAction.NONE);
		given(report.getReporter()).willReturn(reporter);
		given(report.getReportedMember()).willReturn(reportedMember);
		given(abuseReportRepository.findByIdWithMembers(2L)).willReturn(Optional.of(report));

		AdminReportDetailResponse response = adminReportService.getReportDetail(2L, "ABUSE_REPORT");

		assertThat(response.targetType()).isEqualTo("MEMBER_PROFILE");
		assertThat(response.target()).isInstanceOf(AdminAbuseReportDetailResponse.MemberProfileTarget.class);
		var target = (AdminAbuseReportDetailResponse.MemberProfileTarget)response.target();
		assertThat(target.memberId()).isEqualTo(20L);
		assertThat(target.nickname()).isEqualTo("부적절닉네임");
	}

	@Test
	@DisplayName("제보 상세 조회: 지원하지 않는 kind는 예외를 던진다")
	void getReportDetail_invalidKind_throws() {
		assertThatThrownBy(() -> adminReportService.getReportDetail(1L, "UNKNOWN_REPORT"))
			.isInstanceOf(AdminException.class)
			.hasMessageContaining(AdminErrorCode.REPORT_INVALID_KIND.getMessage());
	}

	@Test
	@DisplayName("AbuseReport 통계: 신고 상태와 pending 기준 자동 후보 수를 반환한다")
	void getAbuseReportStats_returnsCountsAndCandidateStats() {
		given(abuseReportRepository.count()).willReturn(10L);
		given(abuseReportRepository.countByStatus(AbuseReportStatus.PENDING)).willReturn(4L);
		given(abuseReportRepository.countByStatus(AbuseReportStatus.ACCEPTED)).willReturn(3L);
		given(abuseReportRepository.countByStatus(AbuseReportStatus.REJECTED)).willReturn(3L);
		given(abuseReportRepository.countByCreatedAtGreaterThanEqual(any(LocalDateTime.class))).willReturn(5L);
		given(abuseReportRepository.findTargetReporterCountsSince(
			eq(AbuseReportTargetType.REVIEW), any(LocalDateTime.class), eq(AbuseReportStatus.PENDING)))
			.willReturn(List.of(
				targetCount(AbuseReportTargetType.REVIEW, 101L, 3L),
				targetCount(AbuseReportTargetType.REVIEW, 102L, 2L)));
		given(abuseReportRepository.findSensitiveReasonReporterCounts(
			anyList(), eq(AbuseReportStatus.PENDING)))
			.willReturn(List.of(
				targetCount(AbuseReportTargetType.REVIEW, 101L, 2L),
				targetCount(AbuseReportTargetType.MEMBER_PROFILE, 20L, 1L)));
		given(abuseReportRepository.findMemberReviewTargetCountsSince(
			eq(AbuseReportTargetType.REVIEW), any(LocalDateTime.class), eq(AbuseReportStatus.PENDING)))
			.willReturn(List.of(
				memberCount(20L, 3L),
				memberCount(21L, 2L)));

		AdminAbuseReportStatsResponse response = adminReportService.getAbuseReportStats();

		assertThat(response.totalReportCount()).isEqualTo(10);
		assertThat(response.pendingReportCount()).isEqualTo(4);
		assertThat(response.last24HoursReportCount()).isEqualTo(5);
		assertThat(response.pendingReviewHideCandidateCount()).isEqualTo(1);
		assertThat(response.pendingSensitiveReasonPriorityCount()).isEqualTo(1);
		assertThat(response.pendingMemberReviewCandidateCount()).isEqualTo(1);
		assertThat(response.lookbackHours()).isEqualTo(24);
		assertThat(response.sensitiveReasonTypes()).containsExactly("PRIVACY", "HATE", "SEXUAL");
	}

	@Test
	@DisplayName("High-priority 신고 큐: 자동 후보 신고만 우선순위 순서로 반환한다")
	void getHighPriorityReports_returnsOnlyCandidateReportsOrderedByPriority() {
		Member reporter = member(10L);
		Member reportedMember = member(20L);
		AbuseReport sensitiveReport = abuseReport(
			1L,
			reporter,
			reportedMember,
			AbuseReportTargetType.REVIEW,
			101L,
			AbuseReportReasonType.PRIVACY,
			LocalDateTime.of(2026, 6, 5, 12, 0));
		AbuseReport normalReport = abuseReport(
			2L,
			reporter,
			member(21L),
			AbuseReportTargetType.REVIEW,
			102L,
			AbuseReportReasonType.ABUSE,
			LocalDateTime.of(2026, 6, 5, 13, 0));
		given(abuseReportRepository.findTargetReporterCountsSince(
			eq(AbuseReportTargetType.REVIEW), any(LocalDateTime.class), eq(AbuseReportStatus.PENDING)))
			.willReturn(List.of(
				targetCount(AbuseReportTargetType.REVIEW, 101L, 3L),
				targetCount(AbuseReportTargetType.REVIEW, 102L, 1L)));
		given(abuseReportRepository.findSensitiveReasonReporterCounts(
			anyList(), eq(AbuseReportStatus.PENDING)))
			.willReturn(List.of(targetCount(AbuseReportTargetType.REVIEW, 101L, 2L)));
		given(abuseReportRepository.findMemberReviewTargetCountsSince(
			eq(AbuseReportTargetType.REVIEW), any(LocalDateTime.class), eq(AbuseReportStatus.PENDING)))
			.willReturn(List.of(memberCount(20L, 3L)));
		given(abuseReportRepository.findByStatus(AbuseReportStatus.PENDING))
			.willReturn(List.of(normalReport, sensitiveReport));

		Page<AdminHighPriorityReportResponse> response = adminReportService.getHighPriorityReports(
			"PENDING", 0, 20);

		assertThat(response.getTotalElements()).isEqualTo(1);
		AdminHighPriorityReportResponse report = response.getContent().get(0);
		assertThat(report.reportId()).isEqualTo(1L);
		assertThat(report.highPriority()).isTrue();
		assertThat(report.temporaryHideCandidate()).isTrue();
		assertThat(report.sensitiveReasonPriority()).isTrue();
		assertThat(report.memberReviewCandidate()).isTrue();
		assertThat(report.reviewReportCount24h()).isEqualTo(3);
		assertThat(report.sensitiveReasonReportCount()).isEqualTo(2);
		assertThat(report.memberReviewReportCount24h()).isEqualTo(3);
		assertThat(report.priorityReasons()).containsExactly(
			"SENSITIVE_REASON_PRIORITY",
			"TEMPORARY_HIDE_CANDIDATE",
			"MEMBER_REVIEW_CANDIDATE");
	}

	@Test
	@DisplayName("ModerationAction HIDE_REVIEW: 승인된 리뷰 신고이면 리뷰를 숨기고 이력을 저장한다")
	void applyModerationAction_hideReview_hidesReviewAndRecordsAction() {
		Member reportedMember = mock(Member.class);
		given(reportedMember.getId()).willReturn(20L);
		AbuseReport report = mock(AbuseReport.class);
		given(report.getId()).willReturn(31L);
		given(report.getStatus()).willReturn(AbuseReportStatus.ACCEPTED);
		given(report.getTargetType()).willReturn(AbuseReportTargetType.REVIEW);
		given(report.getTargetId()).willReturn(123L);
		given(report.getReportedMember()).willReturn(reportedMember);
		given(abuseReportRepository.findByIdWithMembers(31L)).willReturn(Optional.of(report));

		Review review = mock(Review.class);
		given(reviewRepository.findByIdWithDetails(123L)).willReturn(Optional.of(review));
		given(moderationActionRepository.save(any(ModerationAction.class)))
			.willAnswer(invocation -> invocation.getArgument(0));

		AdminModerationActionResponse response = adminReportService.applyModerationAction(
			31L,
			new AdminModerationActionRequest("HIDE_REVIEW", " 욕설 포함 ", true));

		assertThat(response.action()).isEqualTo("HIDE_REVIEW");
		assertThat(response.reason()).isEqualTo("욕설 포함");
		verify(review).hide("욕설 포함", null);
		verify(report).applyModerationAction(AbuseReportAction.HIDE_REVIEW);
		verify(moderationActionRepository).save(argThat(action -> action.getReport() == report
			&& action.getTargetType() == ModerationTargetType.REVIEW
			&& action.getTargetId().equals(123L)
			&& action.getMember() == reportedMember
			&& action.getAction() == AbuseReportAction.HIDE_REVIEW
			&& action.getReason().equals("욕설 포함")));
		verify(reportNotificationService).notifyModerationAction(argThat(action -> action.getReport() == report
			&& action.getAction() == AbuseReportAction.HIDE_REVIEW));
	}

	@Test
	@DisplayName("ModerationAction DELETE_REVIEW: 리뷰 soft delete를 재사용한다")
	void applyModerationAction_deleteReview_softDeletesReview() {
		Member reportedMember = mock(Member.class);
		given(reportedMember.getId()).willReturn(20L);
		AbuseReport report = mock(AbuseReport.class);
		given(report.getId()).willReturn(32L);
		given(report.getStatus()).willReturn(AbuseReportStatus.ACCEPTED);
		given(report.getTargetType()).willReturn(AbuseReportTargetType.REVIEW);
		given(report.getTargetId()).willReturn(124L);
		given(report.getReportedMember()).willReturn(reportedMember);
		given(abuseReportRepository.findByIdWithMembers(32L)).willReturn(Optional.of(report));

		Review review = mock(Review.class);
		given(reviewRepository.findByIdWithDetails(124L)).willReturn(Optional.of(review));
		given(moderationActionRepository.save(any(ModerationAction.class)))
			.willAnswer(invocation -> invocation.getArgument(0));

		adminReportService.applyModerationAction(
			32L,
			new AdminModerationActionRequest("DELETE_REVIEW", "반복 욕설", false));

		verify(review).softDelete();
		verify(review, never()).hide(anyString(), any());
		verify(report).applyModerationAction(AbuseReportAction.DELETE_REVIEW);
		verify(reportNotificationService, never()).notifyModerationAction(any());
	}

	@Test
	@DisplayName("ModerationAction RESTORE_REVIEW: 직전 조치가 삭제이면 삭제와 숨김 상태를 복구한다")
	void applyModerationAction_restoreReview_restoresDeletedReview() {
		Member reportedMember = mock(Member.class);
		given(reportedMember.getId()).willReturn(20L);
		AbuseReport report = mock(AbuseReport.class);
		given(report.getId()).willReturn(33L);
		given(report.getStatus()).willReturn(AbuseReportStatus.ACCEPTED);
		given(report.getTargetType()).willReturn(AbuseReportTargetType.REVIEW);
		given(report.getTargetId()).willReturn(125L);
		given(report.getReportedMember()).willReturn(reportedMember);
		given(report.getModerationAction()).willReturn(AbuseReportAction.DELETE_REVIEW);
		given(abuseReportRepository.findByIdWithMembers(33L)).willReturn(Optional.of(report));

		Review review = mock(Review.class);
		given(reviewRepository.findByIdWithDetails(125L)).willReturn(Optional.of(review));
		given(moderationActionRepository.save(any(ModerationAction.class)))
			.willAnswer(invocation -> invocation.getArgument(0));

		adminReportService.applyModerationAction(
			33L,
			new AdminModerationActionRequest("RESTORE_REVIEW", "오조치 복구", false));

		verify(review).restoreModeration();
		verify(report).applyModerationAction(AbuseReportAction.RESTORE_REVIEW);
	}

	@Test
	@DisplayName("ModerationAction: 승인되지 않은 사용자 신고에는 조치할 수 없다")
	void applyModerationAction_pendingReport_throws() {
		AbuseReport report = mock(AbuseReport.class);
		given(report.getStatus()).willReturn(AbuseReportStatus.PENDING);
		given(abuseReportRepository.findByIdWithMembers(34L)).willReturn(Optional.of(report));

		assertThatThrownBy(() -> adminReportService.applyModerationAction(
			34L,
			new AdminModerationActionRequest("HIDE_REVIEW", "사유", false)))
			.isInstanceOf(AdminException.class)
			.hasMessageContaining(AdminErrorCode.REPORT_MODERATION_NOT_ALLOWED.getMessage());

		verify(moderationActionRepository, never()).save(any());
	}

	@Test
	@DisplayName("ModerationAction: 프로필 신고에는 리뷰 조치를 적용할 수 없다")
	void applyModerationAction_memberProfileReport_throws() {
		AbuseReport report = mock(AbuseReport.class);
		given(report.getStatus()).willReturn(AbuseReportStatus.ACCEPTED);
		given(report.getTargetType()).willReturn(AbuseReportTargetType.MEMBER_PROFILE);
		given(abuseReportRepository.findByIdWithMembers(35L)).willReturn(Optional.of(report));

		assertThatThrownBy(() -> adminReportService.applyModerationAction(
			35L,
			new AdminModerationActionRequest("HIDE_REVIEW", "사유", false)))
			.isInstanceOf(AdminException.class)
			.hasMessageContaining(AdminErrorCode.REPORT_TARGET_TYPE_MISMATCH.getMessage());

		verify(moderationActionRepository, never()).save(any());
	}

	@Test
	@DisplayName("StoreAddReport ACCEPT: Store 생성 + 승인 알림 발송")
	void storeAddReport_accept_createsStoreAndSendsNotification() {
		Member reporter = mock(Member.class);
		Point location = mock(Point.class);
		StoreAddReport report = StoreAddReport.builder()
			.member(reporter)
			.naverPlaceId("123456")
			.name("새빵집")
			.address("서울 마포구 월드컵로 1")
			.roadAddress("서울 마포구 월드컵로 1")
			.phone("02-123-4567")
			.location(location)
			.reason("추가해주세요")
			.build();
		ReflectionTestUtils.setField(report, "id", 13L);
		given(storeContributionReportRepository.findByContributionReportId(13L))
			.willReturn(Optional.of(storeAddContribution(13L, report)));
		given(storeRepository.findByNaverPlaceId("123456")).willReturn(Optional.empty());
		given(storeRepository.findActiveStoresByCompactName("새빵집")).willReturn(List.of());

		Store savedStore = mock(Store.class);
		given(storeRepository.save(any(Store.class))).willReturn(savedStore);

		adminReportService.reviewReport(13L, new AdminReportReviewRequest("STORE_ADD_REPORT", "ACCEPT"));

		verify(storeRepository).save(any(Store.class));
		assertThat(report.getStatus()).isEqualTo(StoreReportStatus.ACCEPTED);
		verify(reportNotificationService).notifyContributionReportResult(
			eq(reporter), eq(savedStore), eq("새빵집"), eq(13L), eq(13L), eq(ReportKind.STORE_ADD_REPORT),
			eq(ReportNotificationAction.APPROVED), anyString());
	}

	@Test
	@DisplayName("StoreAddReport REJECT: Store 생성 없이 거절 알림 발송")
	void storeAddReport_reject_skipsStoreCreationAndSendsNotification() {
		Member reporter = mock(Member.class);
		StoreAddReport report = StoreAddReport.builder()
			.member(reporter)
			.naverPlaceId("123456")
			.name("새빵집")
			.address("서울 마포구 월드컵로 1")
			.location(mock(Point.class))
			.build();
		ReflectionTestUtils.setField(report, "id", 14L);
		given(storeContributionReportRepository.findByContributionReportId(14L))
			.willReturn(Optional.of(storeAddContribution(14L, report)));

		adminReportService.reviewReport(14L, new AdminReportReviewRequest("STORE_ADD_REPORT", "REJECT"));

		verify(storeRepository, never()).save(any(Store.class));
		assertThat(report.getStatus()).isEqualTo(StoreReportStatus.REJECTED);
		verify(reportNotificationService).notifyContributionReportResult(
			eq(reporter), eq(null), eq("새빵집"), eq(14L), eq(14L), eq(ReportKind.STORE_ADD_REPORT),
			eq(ReportNotificationAction.REJECTED), anyString());
	}

	@Test
	@DisplayName("MenuAddReport ACCEPT: Menu 신규 생성 + 이미지 S3 이동 + MenuImageFile 저장 + 알림 발송")
	void menuAddReport_accept_createsMenuAndMovesImages() {
		Store store = mock(Store.class);
		Member reporter = mock(Member.class);
		MenuCategory category = mock(MenuCategory.class);

		MenuAddReport report = MenuAddReport.builder()
			.store(store)
			.member(reporter)
			.menuCategory(category)
			.name("크림빵")
			.price(3500)
			.description("부드러운 크림빵")
			.build();
		ReflectionTestUtils.setField(report, "id", 11L);
		given(menuContributionReportRepository.findByContributionReportId(11L))
			.willReturn(Optional.of(menuAddContribution(11L, report)));

		Menu newMenu = mock(Menu.class);
		given(newMenu.getId()).willReturn(99L);
		given(newMenu.getStore()).willReturn(store);
		given(menuRepository.save(any(Menu.class))).willReturn(newMenu);

		MenuAddReportImageFile img = MenuAddReportImageFile.builder()
			.menuAddReport(report)
			.name("file.jpg")
			.mimeType(MimeType.IMAGE_JPEG)
			.awsKey("menu-add-reports/temp/abc/uuid-file")
			.build();
		given(menuAddReportImageFileRepository.findByMenuAddReportIdIn(List.of(11L)))
			.willReturn(List.of(img));

		adminReportService.reviewReport(11L, new AdminReportReviewRequest("MENU_ADD_REPORT", "ACCEPT"));

		verify(menuRepository).save(any(Menu.class));
		verify(s3Service).copyObject("menu-add-reports/temp/abc/uuid-file", "menus/99/uuid-file");
		verify(s3Service).deleteObject("menu-add-reports/temp/abc/uuid-file");
		verify(menuImageFileRepository).saveAll(anyList());
		assertThat(report.getStatus()).isEqualTo(StoreReportStatus.ACCEPTED);
		verify(reportNotificationService).notifyContributionReportResult(
			eq(reporter), eq(store), eq(null), eq(11L), eq(11L), eq(ReportKind.MENU_ADD_REPORT),
			eq(ReportNotificationAction.APPROVED), anyString());
	}

	@Test
	@DisplayName("MenuAddReport REJECT: Menu 생성 안 함 + 이미지만 S3 삭제 + 거절 알림")
	void menuAddReport_reject_skipsMenuCreation() {
		Store store = mock(Store.class);
		Member reporter = mock(Member.class);
		MenuAddReport report = mock(MenuAddReport.class);
		given(report.getId()).willReturn(12L);
		given(report.getStatus()).willReturn(StoreReportStatus.PENDING);
		given(report.getStore()).willReturn(store);
		given(report.getMember()).willReturn(reporter);
		given(menuContributionReportRepository.findByContributionReportId(12L))
			.willReturn(Optional.of(menuAddContribution(12L, report)));

		MenuAddReportImageFile img = mock(MenuAddReportImageFile.class);
		given(img.getAwsKey()).willReturn("menu-add-reports/temp/xyz/aaa");
		given(menuAddReportImageFileRepository.findByMenuAddReportIdIn(List.of(12L)))
			.willReturn(List.of(img));

		adminReportService.reviewReport(12L, new AdminReportReviewRequest("MENU_ADD_REPORT", "REJECT"));

		verify(menuRepository, never()).save(any(Menu.class));
		verify(s3Service).deleteObject("menu-add-reports/temp/xyz/aaa");
		verify(s3Service, never()).copyObject(anyString(), anyString());
		verify(report).reject();
		verify(reportNotificationService).notifyContributionReportResult(
			eq(reporter), eq(store), eq(null), eq(12L), eq(12L), eq(ReportKind.MENU_ADD_REPORT),
			eq(ReportNotificationAction.REJECTED), anyString());
	}

	@Test
	@DisplayName("StoreReport MOD_BAKERY_TIME ACCEPT: 기존 영업시간 삭제 후 제보 내용으로 재삽입")
	void storeReport_modBakeryTime_accept_replacesBusinessHours() {
		Store store = mock(Store.class);
		given(store.getId()).willReturn(3L);
		Member reporter = mock(Member.class);
		StoreReport report = mock(StoreReport.class);
		given(report.getId()).willReturn(50L);
		given(report.getStatus()).willReturn(StoreReportStatus.PENDING);
		given(report.getRequestType()).willReturn(StoreReportType.MOD_BAKERY_TIME);
		given(report.getStore()).willReturn(store);
		given(report.getMember()).willReturn(reporter);
		given(report.getMessage()).willReturn(
			"{\"hours\":["
				+ "{\"dayOfWeek\":\"MONDAY\",\"closed\":false,\"openTime\":\"09:00\",\"closeTime\":\"21:00\"},"
				+ "{\"dayOfWeek\":\"SUNDAY\",\"closed\":true,\"openTime\":null,\"closeTime\":null}"
				+ "],\"reason\":\"확인\"}");
		given(storeContributionReportRepository.findByContributionReportId(50L))
			.willReturn(Optional.of(storeUpdateContribution(50L, report)));

		adminReportService.reviewReport(50L, new AdminReportReviewRequest("STORE_REPORT", "ACCEPT"));

		verify(storeBusinessHourRepository).deleteByStoreId(3L);
		ArgumentCaptor<List<StoreBusinessHour>> captor = ArgumentCaptor.forClass(List.class);
		verify(storeBusinessHourRepository).saveAll(captor.capture());
		assertThat(captor.getValue()).hasSize(2);
		assertThat(captor.getValue().get(0).getOpenTime()).isEqualTo(LocalTime.of(9, 0));
		assertThat(captor.getValue().get(1).isClosed()).isTrue();
		verify(report).approve();
		verify(reportNotificationService).notifyContributionReportResult(
			eq(reporter), eq(store), eq(null), eq(50L), eq(50L), eq(ReportKind.STORE_REPORT),
			eq(ReportNotificationAction.APPROVED), anyString());
	}

	@Test
	@DisplayName("StoreReport MOD_BAKERY_ADDRESS ACCEPT: Store.update 호출로 주소·위치·region 갱신")
	void storeReport_modBakeryAddress_accept_updatesStore() {
		Store store = mock(Store.class);
		Member reporter = mock(Member.class);
		StoreReport report = mock(StoreReport.class);
		given(report.getId()).willReturn(60L);
		given(report.getStatus()).willReturn(StoreReportStatus.PENDING);
		given(report.getRequestType()).willReturn(StoreReportType.MOD_BAKERY_ADDRESS);
		given(report.getStore()).willReturn(store);
		given(report.getMember()).willReturn(reporter);
		given(report.getMessage()).willReturn(
			"{\"address\":\"서울 강남구 테헤란로 1\","
				+ "\"roadAddress\":\"서울 강남구 테헤란로 1\","
				+ "\"placeName\":\"테스트 빵집\","
				+ "\"latitude\":37.5,\"longitude\":127.05,\"reason\":null}");
		given(storeContributionReportRepository.findByContributionReportId(60L))
			.willReturn(Optional.of(storeUpdateContribution(60L, report)));

		adminReportService.reviewReport(60L, new AdminReportReviewRequest("STORE_REPORT", "ACCEPT"));

		verify(store).update(
			eq(null),
			eq("서울 강남구 테헤란로 1"),
			any(),
			eq("서울"),
			eq("강남구"),
			eq("테헤란로"),
			eq(null),
			eq(null),
			eq(null));
		verify(report).approve();
	}

	@Test
	@DisplayName("StoreReport MOD_BAKERY_IMG ACCEPT: delete + add 이미지 처리 + S3 copy/delete")
	void storeReport_modBakeryImg_accept_addAndDeleteImages() {
		Store store = mock(Store.class);
		given(store.getId()).willReturn(5L);
		Member reporter = mock(Member.class);

		StoreReport report = mock(StoreReport.class);
		given(report.getId()).willReturn(70L);
		given(report.getStatus()).willReturn(StoreReportStatus.PENDING);
		given(report.getRequestType()).willReturn(StoreReportType.MOD_BAKERY_IMG);
		given(report.getStore()).willReturn(store);
		given(report.getMember()).willReturn(reporter);
		given(report.getMessage()).willReturn(
			"{\"deleteImageIds\":[100,101],"
				+ "\"addImages\":[{\"fileName\":\"a.jpg\",\"contentType\":\"image/jpeg\","
				+ "\"awsKey\":\"reports/stores/5/members/9/images/key-a\"}],"
				+ "\"reason\":null}");
		given(storeContributionReportRepository.findByContributionReportId(70L))
			.willReturn(Optional.of(storeUpdateContribution(70L, report)));

		StoreImageFile existing1 = mock(StoreImageFile.class);
		given(existing1.getAwsKey()).willReturn("stores/5/old1");
		StoreImageFile existing2 = mock(StoreImageFile.class);
		given(existing2.getAwsKey()).willReturn("stores/5/old2");
		given(storeImageFileRepository.findByIdInAndStoreId(List.of(100L, 101L), 5L))
			.willReturn(List.of(existing1, existing2));
		given(storeImageFileRepository.findByStoreIdOrderByDisplayOrderAscIdAsc(5L))
			.willReturn(List.of());

		StoreReportImageFile src = StoreReportImageFile.builder()
			.storeReport(report)
			.name("a.jpg")
			.mimeType(MimeType.IMAGE_JPEG)
			.awsKey("reports/stores/5/members/9/images/key-a")
			.build();
		given(storeReportImageFileRepository.findByStoreReportIdIn(List.of(70L)))
			.willReturn(List.of(src));

		adminReportService.reviewReport(70L, new AdminReportReviewRequest("STORE_REPORT", "ACCEPT"));

		verify(s3Service).deleteObject("stores/5/old1");
		verify(s3Service).deleteObject("stores/5/old2");
		verify(storeImageFileRepository).deleteAll(List.of(existing1, existing2));
		verify(s3Service).copyObject("reports/stores/5/members/9/images/key-a", "stores/5/key-a");
		verify(s3Service).deleteObject("reports/stores/5/members/9/images/key-a");
		verify(storeImageFileRepository).saveAll(anyList());
		verify(report).approve();
	}

	@Test
	@DisplayName("StoreReport MOD_BREAD ACCEPT: menu.price 갱신 + 이미지 add/delete + S3 처리")
	void storeReport_modBread_accept_updatesMenuAndImages() {
		Store store = mock(Store.class);
		Member reporter = mock(Member.class);
		Menu menu = mock(Menu.class);
		given(menu.getId()).willReturn(200L);

		StoreReport report = mock(StoreReport.class);
		given(report.getId()).willReturn(80L);
		given(report.getStatus()).willReturn(StoreReportStatus.PENDING);
		given(report.getRequestType()).willReturn(StoreReportType.MOD_BREAD);
		given(report.getStore()).willReturn(store);
		given(report.getMember()).willReturn(reporter);
		given(report.getMenu()).willReturn(menu);
		given(report.getMessage()).willReturn(
			"{\"price\":4500,\"deleteImageIds\":[301],"
				+ "\"addImages\":[{\"fileName\":\"m.jpg\",\"contentType\":\"image/jpeg\","
				+ "\"awsKey\":\"reports/stores/6/menus/200/members/9/key-m\"}],"
				+ "\"reason\":null}");
		given(menuContributionReportRepository.findByContributionReportId(80L))
			.willReturn(Optional.of(menuUpdateContribution(80L, report)));

		MenuImageFile existing = mock(MenuImageFile.class);
		given(existing.getAwsKey()).willReturn("menus/200/oldmenu");
		given(menuImageFileRepository.findByIdInAndMenuId(List.of(301L), 200L))
			.willReturn(List.of(existing));
		given(menuImageFileRepository.findImagesByMenuId(200L)).willReturn(List.of());

		StoreReportImageFile src = StoreReportImageFile.builder()
			.storeReport(report)
			.name("m.jpg")
			.mimeType(MimeType.IMAGE_JPEG)
			.awsKey("reports/stores/6/menus/200/members/9/key-m")
			.build();
		given(storeReportImageFileRepository.findByStoreReportIdIn(List.of(80L)))
			.willReturn(List.of(src));

		adminReportService.reviewReport(80L, new AdminReportReviewRequest("MENU_REPORT", "ACCEPT"));

		verify(menu).update(null, 4500, null, null);
		verify(s3Service).deleteObject("menus/200/oldmenu");
		verify(s3Service).copyObject("reports/stores/6/menus/200/members/9/key-m", "menus/200/key-m");
		verify(s3Service).deleteObject("reports/stores/6/menus/200/members/9/key-m");
		verify(report).approve();
	}

	@Test
	@DisplayName("AbuseReport ACCEPT: 신고 상태를 승인 처리하고 신고자에게 결과 알림을 발송한다")
	void abuseReport_accept_updatesStatusAndNotifiesReporter() {
		AbuseReport report = mock(AbuseReport.class);
		given(report.getStatus()).willReturn(AbuseReportStatus.PENDING);
		given(abuseReportRepository.findByIdWithMembers(31L)).willReturn(Optional.of(report));

		adminReportService.reviewReport(31L, new AdminReportReviewRequest("ABUSE_REPORT", "ACCEPT"));

		verify(report).accept();
		verify(report, never()).reject();
		verify(reportNotificationService).notifyAbuseReportResult(report);
		verify(reportNotificationService, never()).notifyReportResult(
			any(), any(), any(), any(), any(), anyString());
	}

	@Test
	@DisplayName("AbuseReport REJECT: 신고 상태를 거절 처리하고 신고자에게 결과 알림을 발송한다")
	void abuseReport_reject_updatesStatusAndNotifiesReporter() {
		AbuseReport report = mock(AbuseReport.class);
		given(report.getStatus()).willReturn(AbuseReportStatus.PENDING);
		given(abuseReportRepository.findByIdWithMembers(32L)).willReturn(Optional.of(report));

		adminReportService.reviewReport(32L, new AdminReportReviewRequest("ABUSE_REPORT", "REJECT"));

		verify(report).reject();
		verify(report, never()).accept();
		verify(reportNotificationService).notifyAbuseReportResult(report);
		verify(reportNotificationService, never()).notifyReportResult(
			any(), any(), any(), any(), any(), anyString());
	}

	@Test
	@DisplayName("이미 처리된 제보는 다시 리뷰할 수 없다")
	void reviewReport_alreadyReviewed_throws() {
		StoreReport report = mock(StoreReport.class);
		given(report.getStatus()).willReturn(StoreReportStatus.ACCEPTED);
		given(storeContributionReportRepository.findByContributionReportId(99L))
			.willReturn(Optional.of(storeUpdateContribution(99L, report)));

		assertThatThrownBy(() -> adminReportService.reviewReport(99L,
			new AdminReportReviewRequest("STORE_REPORT", "ACCEPT")))
			.isInstanceOf(AdminException.class)
			.hasMessageContaining(AdminErrorCode.REPORT_ALREADY_REVIEWED.getMessage());

		verify(report, never()).approve();
		verify(reportNotificationService, never()).notifyReportResult(
			any(), any(), any(), any(), any(), anyString());
	}

	@Test
	@DisplayName("StoreReport MOD_BAKERY_ADDRESS ACCEPT: StoreAddressChangedEvent가 해당 storeId로 발행된다")
	void storeReport_modBakeryAddress_accept_publishesAddressEvent() {
		Store store = mock(Store.class);
		given(store.getId()).willReturn(7L);
		Member reporter = mock(Member.class);
		StoreReport report = mock(StoreReport.class);
		given(report.getId()).willReturn(61L);
		given(report.getStatus()).willReturn(StoreReportStatus.PENDING);
		given(report.getRequestType()).willReturn(StoreReportType.MOD_BAKERY_ADDRESS);
		given(report.getStore()).willReturn(store);
		given(report.getMember()).willReturn(reporter);
		given(report.getMessage()).willReturn(
			"{\"address\":\"서울 마포구 와우산로 1\","
				+ "\"roadAddress\":\"서울 마포구 와우산로 1\","
				+ "\"placeName\":\"빵집\","
				+ "\"latitude\":37.55,\"longitude\":126.93,\"reason\":null}");
		given(storeContributionReportRepository.findByContributionReportId(61L))
			.willReturn(Optional.of(storeUpdateContribution(61L, report)));

		adminReportService.reviewReport(61L, new AdminReportReviewRequest("STORE_REPORT", "ACCEPT"));

		ArgumentCaptor<StoreAddressChangedEvent> captor = ArgumentCaptor.forClass(StoreAddressChangedEvent.class);
		verify(eventPublisher).publishEvent(captor.capture());
		assertThat(captor.getValue().storeId()).isEqualTo(7L);
	}

	@Test
	@DisplayName("MenuAddReport ACCEPT: StoreMenusChangedEvent가 해당 storeId로 발행된다")
	void menuAddReport_accept_publishesMenusEvent() {
		Store store = mock(Store.class);
		given(store.getId()).willReturn(8L);
		Member reporter = mock(Member.class);
		MenuCategory category = mock(MenuCategory.class);

		MenuAddReport report = MenuAddReport.builder()
			.store(store)
			.member(reporter)
			.menuCategory(category)
			.name("팥빵")
			.price(2000)
			.description("달콤한 팥빵")
			.build();
		ReflectionTestUtils.setField(report, "id", 13L);
		given(menuContributionReportRepository.findByContributionReportId(13L))
			.willReturn(Optional.of(menuAddContribution(13L, report)));

		Menu newMenu = mock(Menu.class);
		given(newMenu.getStore()).willReturn(store);
		given(menuRepository.save(any(Menu.class))).willReturn(newMenu);
		given(menuAddReportImageFileRepository.findByMenuAddReportIdIn(List.of(13L))).willReturn(List.of());

		adminReportService.reviewReport(13L, new AdminReportReviewRequest("MENU_ADD_REPORT", "ACCEPT"));

		ArgumentCaptor<StoreMenusChangedEvent> captor = ArgumentCaptor.forClass(StoreMenusChangedEvent.class);
		verify(eventPublisher).publishEvent(captor.capture());
		assertThat(captor.getValue().storeId()).isEqualTo(8L);
	}

	@Test
	@DisplayName("kind가 잘못되면 예외를 던진다")
	void reviewReport_invalidKind_throws() {
		assertThatThrownBy(() -> adminReportService.reviewReport(1L,
			new AdminReportReviewRequest("UNKNOWN", "ACCEPT")))
			.isInstanceOf(AdminException.class);
	}

	@Test
	@DisplayName("action이 ACCEPT/REJECT 외이면 예외를 던진다")
	void reviewReport_invalidAction_throws() {
		assertThatThrownBy(() -> adminReportService.reviewReport(1L,
			new AdminReportReviewRequest("STORE_REPORT", "MAYBE")))
			.isInstanceOf(AdminException.class);
	}

	private Member member(Long id) {
		Member member = mock(Member.class);
		given(member.getId()).willReturn(id);
		return member;
	}

	private StoreContributionReport storeAddContribution(Long contributionReportId, StoreAddReport report) {
		ContributionReport contributionReport = contributionReport(
			contributionReportId, ContributionTargetType.STORE, ContributionReportType.ADD);
		return StoreContributionReport.builder()
			.contributionReport(contributionReport)
			.storeAddReport(report)
			.build();
	}

	private StoreContributionReport storeUpdateContribution(Long contributionReportId, StoreReport report) {
		ContributionReport contributionReport = contributionReport(
			contributionReportId, ContributionTargetType.STORE, ContributionReportType.UPDATE);
		return StoreContributionReport.builder()
			.contributionReport(contributionReport)
			.storeReport(report)
			.build();
	}

	private MenuContributionReport menuAddContribution(Long contributionReportId, MenuAddReport report) {
		ContributionReport contributionReport = contributionReport(
			contributionReportId, ContributionTargetType.MENU, ContributionReportType.ADD);
		return MenuContributionReport.builder()
			.contributionReport(contributionReport)
			.menuAddReport(report)
			.build();
	}

	private MenuContributionReport menuUpdateContribution(Long contributionReportId, StoreReport report) {
		ContributionReport contributionReport = contributionReport(
			contributionReportId, ContributionTargetType.MENU, ContributionReportType.UPDATE);
		return MenuContributionReport.builder()
			.contributionReport(contributionReport)
			.storeReport(report)
			.build();
	}

	private ContributionReport contributionReport(
			Long id,
			ContributionTargetType targetType,
			ContributionReportType reportType) {
		ContributionReport contributionReport = ContributionReport.builder()
			.targetType(targetType)
			.reportType(reportType)
			.build();
		ReflectionTestUtils.setField(contributionReport, "id", id);
		return contributionReport;
	}

	private AbuseReport abuseReport(
			Long id,
			Member reporter,
			Member reportedMember,
			AbuseReportTargetType targetType,
			Long targetId,
			AbuseReportReasonType reasonType,
			LocalDateTime createdAt) {
		AbuseReport report = mock(AbuseReport.class);
		given(report.getId()).willReturn(id);
		given(report.getReporter()).willReturn(reporter);
		given(report.getReportedMember()).willReturn(reportedMember);
		given(report.getTargetType()).willReturn(targetType);
		given(report.getTargetId()).willReturn(targetId);
		given(report.getReasonType()).willReturn(reasonType);
		given(report.getReasonDetail()).willReturn("신고 사유");
		given(report.getStatus()).willReturn(AbuseReportStatus.PENDING);
		given(report.getModerationAction()).willReturn(AbuseReportAction.NONE);
		given(report.getCreatedAt()).willReturn(createdAt);
		return report;
	}

	private AbuseReportTargetCountProjection targetCount(
			AbuseReportTargetType targetType,
			Long targetId,
			Long reporterCount) {
		return new AbuseReportTargetCountProjection() {
			@Override
			public AbuseReportTargetType getTargetType() {
				return targetType;
			}

			@Override
			public Long getTargetId() {
				return targetId;
			}

			@Override
			public Long getReporterCount() {
				return reporterCount;
			}
		};
	}

	private AbuseReportMemberCountProjection memberCount(Long reportedMemberId, Long reviewTargetCount) {
		return new AbuseReportMemberCountProjection() {
			@Override
			public Long getReportedMemberId() {
				return reportedMemberId;
			}

			@Override
			public Long getReviewTargetCount() {
				return reviewTargetCount;
			}
		};
	}
}
