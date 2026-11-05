package sweetgirl.croissant.abuse.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.abuse.entity.AbuseReport;
import sweetgirl.croissant.abuse.enums.AbuseReportReasonType;
import sweetgirl.croissant.abuse.enums.AbuseReportStatus;
import sweetgirl.croissant.abuse.enums.AbuseReportTargetType;
import sweetgirl.croissant.abuse.repository.AbuseReportRepository;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.notification.service.AdminReportNotificationPublisher;

@ExtendWith(MockitoExtension.class)
@DisplayName("AbuseReportReminderScheduler")
class AbuseReportReminderSchedulerTest {

	@Mock
	private AbuseReportRepository abuseReportRepository;
	@Mock
	private AdminReportNotificationPublisher publisher;

	@InjectMocks
	private AbuseReportReminderScheduler scheduler;

	@Test
	@DisplayName("24시간 넘게 처리 안 된 신고를 슬랙에 알리고 건당 한 번만 표시한다")
	void remindsOnce() {
		Member reporter = Member.builder().nickname("신고자").build();
		Member reported = Member.builder().nickname("피신고자").build();
		AbuseReport report = new AbuseReport(reporter, AbuseReportTargetType.RECORD, 10L, reported,
			AbuseReportReasonType.SPAM, null);
		given(abuseReportRepository.findOverdue(eq(AbuseReportStatus.PENDING), any())).willReturn(List.of(report));

		scheduler.remindOverdue();

		then(publisher).should().publishAbuseReportsOverdue(List.of(report));
		assertThat(report.getRemindedAt()).isNotNull();
	}

	@Test
	@DisplayName("밀린 신고가 없으면 아무것도 보내지 않는다")
	void nothingOverdue() {
		given(abuseReportRepository.findOverdue(eq(AbuseReportStatus.PENDING), any())).willReturn(List.of());

		scheduler.remindOverdue();
		then(publisher).shouldHaveNoInteractions();
	}
}
