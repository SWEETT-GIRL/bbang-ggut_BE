package sweetgirl.croissant.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import sweetgirl.croissant.abuse.entity.AbuseReport;
import sweetgirl.croissant.abuse.enums.AbuseReportTargetType;
import sweetgirl.croissant.contribution.entity.ContributionReport;
import sweetgirl.croissant.contribution.entity.MenuContributionReport;
import sweetgirl.croissant.contribution.entity.StoreContributionReport;
import sweetgirl.croissant.contribution.enums.ContributionReportType;
import sweetgirl.croissant.inquiry.entity.Inquiry;
import sweetgirl.croissant.notification.event.OperationalNotificationEvent;
import sweetgirl.croissant.notification.event.OperationalNotificationEvent.Channel;

@ExtendWith(MockitoExtension.class)
class AdminReportNotificationPublisherTest {

	@Mock
	private ApplicationEventPublisher eventPublisher;

	@InjectMocks
	private AdminReportNotificationPublisher publisher;

	@Test
	@DisplayName("가게 제보: ADD면 '새 가게 제보 접수' 제목으로 발행한다")
	void storeReport_add() {
		publisher.publishStoreContributionReportCreated(storeReport(ContributionReportType.ADD));

		OperationalNotificationEvent event = captured();
		assertThat(event.channel()).isEqualTo(Channel.REPORT);
		assertThat(event.title()).isEqualTo("🏪 새 가게 제보 접수");
	}

	@Test
	@DisplayName("가게 제보: UPDATE면 '가게 수정 제보 접수' 제목으로 발행한다")
	void storeReport_update() {
		publisher.publishStoreContributionReportCreated(storeReport(ContributionReportType.UPDATE));

		assertThat(captured().title()).isEqualTo("🏪 가게 수정 제보 접수");
	}

	@Test
	@DisplayName("메뉴 제보: ADD면 '새 메뉴 제보 접수' 제목으로 발행한다")
	void menuReport_add() {
		publisher.publishMenuContributionReportCreated(menuReport(ContributionReportType.ADD));

		assertThat(captured().title()).isEqualTo("🥐 새 메뉴 제보 접수");
	}

	@Test
	@DisplayName("메뉴 제보: UPDATE면 '메뉴 수정 제보 접수' 제목으로 발행한다")
	void menuReport_update() {
		publisher.publishMenuContributionReportCreated(menuReport(ContributionReportType.UPDATE));

		assertThat(captured().title()).isEqualTo("🥐 메뉴 수정 제보 접수");
	}

	@Test
	@DisplayName("신고: REVIEW면 '리뷰 신고 접수' 제목으로 발행한다")
	void abuseReport_review() {
		publisher.publishAbuseReportCreated(abuseReport(AbuseReportTargetType.REVIEW));

		OperationalNotificationEvent event = captured();
		assertThat(event.channel()).isEqualTo(Channel.ABUSE);
		assertThat(event.title()).isEqualTo("🚨 리뷰 신고 접수");
	}

	@Test
	@DisplayName("신고: MEMBER_PROFILE이면 '프로필 신고 접수' 제목으로 발행한다")
	void abuseReport_profile() {
		publisher.publishAbuseReportCreated(abuseReport(AbuseReportTargetType.MEMBER_PROFILE));

		assertThat(captured().title()).isEqualTo("🚨 프로필 신고 접수");
	}

	@Test
	@DisplayName("문의: '문의 접수' 제목으로 발행한다")
	void inquiry() {
		publisher.publishInquiryCreated(mock(Inquiry.class));

		assertThat(captured().title()).isEqualTo("💬 문의 접수");
	}

	private StoreContributionReport storeReport(ContributionReportType reportType) {
		StoreContributionReport report = mock(StoreContributionReport.class);
		ContributionReport contributionReport = mock(ContributionReport.class);
		given(report.getContributionReport()).willReturn(contributionReport);
		given(contributionReport.getReportType()).willReturn(reportType);
		return report;
	}

	private MenuContributionReport menuReport(ContributionReportType reportType) {
		MenuContributionReport report = mock(MenuContributionReport.class);
		ContributionReport contributionReport = mock(ContributionReport.class);
		given(report.getContributionReport()).willReturn(contributionReport);
		given(contributionReport.getReportType()).willReturn(reportType);
		return report;
	}

	private AbuseReport abuseReport(AbuseReportTargetType targetType) {
		AbuseReport report = mock(AbuseReport.class);
		given(report.getTargetType()).willReturn(targetType);
		return report;
	}

	private OperationalNotificationEvent captured() {
		ArgumentCaptor<OperationalNotificationEvent> captor = ArgumentCaptor
			.forClass(OperationalNotificationEvent.class);
		verify(eventPublisher).publishEvent(captor.capture());
		return captor.getValue();
	}
}
