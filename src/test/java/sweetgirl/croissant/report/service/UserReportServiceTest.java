package sweetgirl.croissant.report.service;

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

import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.repository.MemberRepository;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.report.code.UserReportErrorCode;
import sweetgirl.croissant.report.dto.request.UserReportCreateRequest;
import sweetgirl.croissant.report.dto.response.UserReportCreateResponse;
import sweetgirl.croissant.report.entity.UserReport;
import sweetgirl.croissant.report.enums.ReportReasonType;
import sweetgirl.croissant.report.enums.ReportTargetType;
import sweetgirl.croissant.report.exception.UserReportException;
import sweetgirl.croissant.report.repository.UserReportRepository;
import sweetgirl.croissant.review.entity.Review;
import sweetgirl.croissant.review.repository.ReviewRepository;

@ExtendWith(MockitoExtension.class)
class UserReportServiceTest {

	@Mock
	MemberReader memberReader;
	@Mock
	MemberRepository memberRepository;
	@Mock
	ReviewRepository reviewRepository;
	@Mock
	UserReportRepository userReportRepository;

	UserReportService userReportService;

	@BeforeEach
	void setUp() {
		userReportService = new UserReportService(
			memberReader,
			memberRepository,
			reviewRepository,
			userReportRepository);
	}

	@Test
	void createReport_savesReviewReport() {
		Member reporter = member(1L);
		Member author = member(2L);
		Review review = mock(Review.class);
		given(review.getMember()).willReturn(author);
		given(memberReader.getMember(1L)).willReturn(reporter);
		given(reviewRepository.findActiveByIdWithDetails(10L)).willReturn(Optional.of(review));
		given(userReportRepository.save(any(UserReport.class))).willAnswer(invocation -> {
			UserReport report = invocation.getArgument(0);
			ReflectionTestUtils.setField(report, "id", 100L);
			ReflectionTestUtils.setField(report, "createdAt", LocalDateTime.of(2026, 6, 4, 12, 0));
			return report;
		});

		UserReportCreateResponse response = userReportService.createReport(1L,
			new UserReportCreateRequest(ReportTargetType.REVIEW, 10L, ReportReasonType.ABUSE, "  욕설  "));

		assertThat(response.reportId()).isEqualTo(100L);
		assertThat(response.status().name()).isEqualTo("PENDING");
		ArgumentCaptor<UserReport> captor = ArgumentCaptor.forClass(UserReport.class);
		verify(userReportRepository).save(captor.capture());
		assertThat(captor.getValue().getReasonDetail()).isEqualTo("욕설");
		assertThat(captor.getValue().getReportedMember()).isEqualTo(author);
	}

	@Test
	void createReport_rejectsSelfReviewReport() {
		Member reporter = member(1L);
		Review review = mock(Review.class);
		given(review.getMember()).willReturn(reporter);
		given(memberReader.getMember(1L)).willReturn(reporter);
		given(reviewRepository.findActiveByIdWithDetails(10L)).willReturn(Optional.of(review));

		assertThatThrownBy(() -> userReportService.createReport(1L,
			new UserReportCreateRequest(ReportTargetType.REVIEW, 10L, ReportReasonType.ABUSE, null)))
			.isInstanceOf(UserReportException.class)
			.hasMessageContaining(UserReportErrorCode.REPORT_SELF_TARGET_NOT_ALLOWED.getMessage());

		verify(userReportRepository, never()).save(any());
	}

	@Test
	void createReport_rejectsDuplicatePendingOrAcceptedReport() {
		Member reporter = member(1L);
		Member author = member(2L);
		Review review = mock(Review.class);
		given(review.getMember()).willReturn(author);
		given(memberReader.getMember(1L)).willReturn(reporter);
		given(reviewRepository.findActiveByIdWithDetails(10L)).willReturn(Optional.of(review));
		given(userReportRepository.existsByReporterIdAndTargetTypeAndTargetIdAndStatusIn(
			any(), any(), any(), any())).willReturn(true);

		assertThatThrownBy(() -> userReportService.createReport(1L,
			new UserReportCreateRequest(ReportTargetType.REVIEW, 10L, ReportReasonType.ABUSE, null)))
			.isInstanceOf(UserReportException.class)
			.hasMessageContaining(UserReportErrorCode.REPORT_ALREADY_EXISTS.getMessage());

		verify(userReportRepository, never()).save(any());
	}

	private Member member(Long id) {
		Member member = mock(Member.class);
		given(member.getId()).willReturn(id);
		return member;
	}
}
