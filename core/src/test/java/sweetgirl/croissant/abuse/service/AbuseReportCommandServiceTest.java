package sweetgirl.croissant.abuse.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.abuse.code.AbuseReportErrorCode;
import sweetgirl.croissant.abuse.dto.request.AbuseReportCreateRequest;
import sweetgirl.croissant.abuse.dto.response.AbuseReportCreateResponse;
import sweetgirl.croissant.abuse.entity.AbuseReport;
import sweetgirl.croissant.abuse.entity.ModerationAction;
import sweetgirl.croissant.abuse.enums.AbuseReportAction;
import sweetgirl.croissant.abuse.enums.AbuseReportReasonType;
import sweetgirl.croissant.abuse.enums.AbuseReportTargetType;
import sweetgirl.croissant.abuse.exception.AbuseReportException;
import sweetgirl.croissant.abuse.repository.AbuseReportRepository;
import sweetgirl.croissant.abuse.repository.ModerationActionRepository;
import sweetgirl.croissant.alarm.service.ReportNotificationService;
import sweetgirl.croissant.breadlog.entity.BreadRecord;
import sweetgirl.croissant.breadlog.repository.BreadRecordRepository;
import sweetgirl.croissant.comment.entity.BreadRecordComment;
import sweetgirl.croissant.comment.repository.BreadRecordCommentRepository;
import sweetgirl.croissant.course.repository.CourseRepository;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.repository.MemberRepository;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.notification.service.AdminReportNotificationPublisher;
import sweetgirl.croissant.review.entity.Review;
import sweetgirl.croissant.review.repository.ReviewRepository;

@ExtendWith(MockitoExtension.class)
class AbuseReportCommandServiceTest {

	@Mock
	MemberReader memberReader;
	@Mock
	MemberRepository memberRepository;
	@Mock
	ReviewRepository reviewRepository;
	@Mock
	CourseRepository courseRepository;
	@Mock
	AbuseReportRepository abuseReportRepository;
	@Mock
	AdminReportNotificationPublisher adminReportNotificationPublisher;
	@Mock
	BreadRecordRepository breadRecordRepository;
	@Mock
	BreadRecordCommentRepository commentRepository;
	@Mock
	ModerationActionRepository moderationActionRepository;
	@Mock
	ReportNotificationService reportNotificationService;

	AbuseReportCommandService abuseReportCommandService;

	@BeforeEach
	void setUp() {
		abuseReportCommandService = new AbuseReportCommandService(
			memberReader,
			memberRepository,
			reviewRepository,
			courseRepository,
			abuseReportRepository,
			adminReportNotificationPublisher,
			breadRecordRepository,
			commentRepository,
			moderationActionRepository,
			reportNotificationService);
	}

	@Test
	void createReport_savesReviewReport() {
		Member reporter = member(1L);
		Member author = member(2L);
		Review review = mock(Review.class);
		given(review.getMember()).willReturn(author);
		given(memberReader.getMember(1L)).willReturn(reporter);
		given(reviewRepository.findActiveByIdWithDetails(10L)).willReturn(Optional.of(review));
		given(abuseReportRepository.save(any(AbuseReport.class))).willAnswer(invocation -> {
			AbuseReport report = invocation.getArgument(0);
			ReflectionTestUtils.setField(report, "id", 100L);
			ReflectionTestUtils.setField(report, "createdAt", LocalDateTime.of(2026, 6, 4, 12, 0));
			return report;
		});

		AbuseReportCreateResponse response = abuseReportCommandService.createReport(1L,
			new AbuseReportCreateRequest(AbuseReportTargetType.REVIEW, 10L, AbuseReportReasonType.ABUSE, "  욕설  "));

		assertThat(response.reportId()).isEqualTo(100L);
		assertThat(response.status().name()).isEqualTo("PENDING");
		ArgumentCaptor<AbuseReport> captor = ArgumentCaptor.forClass(AbuseReport.class);
		verify(abuseReportRepository).save(captor.capture());
		assertThat(captor.getValue().getReasonDetail()).isEqualTo("욕설");
		assertThat(captor.getValue().getReportedMember()).isEqualTo(author);
		verify(adminReportNotificationPublisher).publishAbuseReportCreated(captor.getValue());
	}

	@Test
	void createReport_rejectsSelfReviewReport() {
		Member reporter = member(1L);
		Review review = mock(Review.class);
		given(review.getMember()).willReturn(reporter);
		given(memberReader.getMember(1L)).willReturn(reporter);
		given(reviewRepository.findActiveByIdWithDetails(10L)).willReturn(Optional.of(review));

		assertThatThrownBy(() -> abuseReportCommandService.createReport(1L,
			new AbuseReportCreateRequest(AbuseReportTargetType.REVIEW, 10L, AbuseReportReasonType.ABUSE, null)))
			.isInstanceOf(AbuseReportException.class)
			.hasMessageContaining(AbuseReportErrorCode.REPORT_SELF_TARGET_NOT_ALLOWED.getMessage());

		verify(abuseReportRepository, never()).save(any());
	}

	@Test
	void createReport_rejectsDuplicatePendingOrAcceptedReport() {
		Member reporter = member(1L);
		Member author = member(2L);
		Review review = mock(Review.class);
		given(review.getMember()).willReturn(author);
		given(memberReader.getMember(1L)).willReturn(reporter);
		given(reviewRepository.findActiveByIdWithDetails(10L)).willReturn(Optional.of(review));
		given(abuseReportRepository.existsByReporterIdAndTargetTypeAndTargetIdAndStatusIn(
			any(), any(), any(), any())).willReturn(true);

		assertThatThrownBy(() -> abuseReportCommandService.createReport(1L,
			new AbuseReportCreateRequest(AbuseReportTargetType.REVIEW, 10L, AbuseReportReasonType.ABUSE, null)))
			.isInstanceOf(AbuseReportException.class)
			.hasMessageContaining(AbuseReportErrorCode.REPORT_ALREADY_EXISTS.getMessage());

		verify(abuseReportRepository, never()).save(any());
	}

	private Member member(Long id) {
		Member member = mock(Member.class);
		given(member.getId()).willReturn(id);
		return member;
	}

	@Test
	@DisplayName("빵로그 신고: 공개 기록만 대상이고, 서로 다른 신고자 3명이면 바로 숨기고 작성자에게 알린다")
	void recordReport_autoHidesAtThreeReporters() {
		Member reporter = member(1L);
		Member author = member(2L);
		BreadRecord record = BreadRecord.builder().member(author).storeName("밀도").isPublic(true).build();
		ReflectionTestUtils.setField(record, "id", 10L);
		given(memberReader.getMember(1L)).willReturn(reporter);
		given(breadRecordRepository.findByIdAndDeletedAtIsNull(10L)).willReturn(Optional.of(record));
		given(abuseReportRepository.existsByReporterIdAndTargetTypeAndTargetIdAndStatusIn(
			eq(1L), eq(AbuseReportTargetType.RECORD), eq(10L), any())).willReturn(false);
		given(abuseReportRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));
		given(abuseReportRepository.countDistinctReporters(eq(AbuseReportTargetType.RECORD), eq(10L), any()))
			.willReturn(3L);
		given(moderationActionRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));

		abuseReportCommandService.createReport(1L, new AbuseReportCreateRequest(
			AbuseReportTargetType.RECORD, 10L, AbuseReportReasonType.SPAM, null));

		assertThat(record.isHidden()).isTrue();
		assertThat(record.getHiddenBy()).isEqualTo("SYSTEM");
		ArgumentCaptor<ModerationAction> action = ArgumentCaptor.forClass(ModerationAction.class);
		then(moderationActionRepository).should().save(action.capture());
		assertThat(action.getValue().getAction()).isEqualTo(AbuseReportAction.HIDE_RECORD);
		assertThat(action.getValue().getMember()).isSameAs(author);
		then(reportNotificationService).should().notifyModerationAction(action.getValue());
		then(adminReportNotificationPublisher).should().publishAbuseReportCreated(any());
	}

	@Test
	@DisplayName("댓글 신고: 2명까지는 숨기지 않고, 비공개 빵로그는 신고 대상이 아니다")
	void commentReport_belowThreshold_andPrivateRecordRejected() {
		Member reporter = member(1L);
		Member author = member(2L);
		BreadRecord record = BreadRecord.builder().member(author).storeName("밀도").isPublic(true).build();
		ReflectionTestUtils.setField(record, "id", 10L);
		BreadRecordComment comment = BreadRecordComment.of(record, author, "댓글");
		ReflectionTestUtils.setField(comment, "id", 100L);
		given(memberReader.getMember(1L)).willReturn(reporter);
		given(commentRepository.findActiveByIdWithRecord(100L)).willReturn(Optional.of(comment));
		given(abuseReportRepository.existsByReporterIdAndTargetTypeAndTargetIdAndStatusIn(
			eq(1L), eq(AbuseReportTargetType.COMMENT), eq(100L), any())).willReturn(false);
		given(abuseReportRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));
		given(abuseReportRepository.countDistinctReporters(eq(AbuseReportTargetType.COMMENT), eq(100L), any()))
			.willReturn(2L);

		abuseReportCommandService.createReport(1L, new AbuseReportCreateRequest(
			AbuseReportTargetType.COMMENT, 100L, AbuseReportReasonType.ABUSE, null));

		assertThat(comment.isHidden()).isFalse();
		then(moderationActionRepository).shouldHaveNoInteractions();

		BreadRecord privateRecord = BreadRecord.builder().member(author).storeName("밀도").isPublic(false).build();
		given(breadRecordRepository.findByIdAndDeletedAtIsNull(11L)).willReturn(Optional.of(privateRecord));
		assertThatThrownBy(() -> abuseReportCommandService.createReport(1L, new AbuseReportCreateRequest(
			AbuseReportTargetType.RECORD, 11L, AbuseReportReasonType.SPAM, null)))
			.isInstanceOf(AbuseReportException.class);
	}
}
