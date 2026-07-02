package sweetgirl.croissant.abuse.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
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
import sweetgirl.croissant.abuse.enums.AbuseReportReasonType;
import sweetgirl.croissant.abuse.enums.AbuseReportTargetType;
import sweetgirl.croissant.abuse.exception.AbuseReportException;
import sweetgirl.croissant.abuse.repository.AbuseReportRepository;
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
	AbuseReportRepository abuseReportRepository;
	@Mock
	AdminReportNotificationPublisher adminReportNotificationPublisher;

	AbuseReportCommandService abuseReportCommandService;

	@BeforeEach
	void setUp() {
		abuseReportCommandService = new AbuseReportCommandService(
			memberReader,
			memberRepository,
			reviewRepository,
			abuseReportRepository,
			adminReportNotificationPublisher);
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
}
