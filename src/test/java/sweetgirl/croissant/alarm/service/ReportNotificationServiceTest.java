package sweetgirl.croissant.alarm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.abuse.entity.AbuseReport;
import sweetgirl.croissant.abuse.entity.ModerationAction;
import sweetgirl.croissant.abuse.enums.AbuseReportAction;
import sweetgirl.croissant.abuse.enums.AbuseReportReasonType;
import sweetgirl.croissant.abuse.enums.AbuseReportTargetType;
import sweetgirl.croissant.abuse.enums.ModerationTargetType;
import sweetgirl.croissant.alarm.entity.FcmToken;
import sweetgirl.croissant.alarm.entity.ReportNotification;
import sweetgirl.croissant.alarm.enums.DeviceOs;
import sweetgirl.croissant.alarm.enums.ReportNotificationAction;
import sweetgirl.croissant.alarm.repository.FcmTokenRepository;
import sweetgirl.croissant.alarm.repository.ReportNotificationRepository;
import sweetgirl.croissant.common.enums.ReportKind;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.store.entity.Store;

@ExtendWith(MockitoExtension.class)
class ReportNotificationServiceTest {

	@Mock
	ReportNotificationRepository reportNotificationRepository;
	@Mock
	FcmTokenRepository fcmTokenRepository;
	@Mock
	FcmService fcmService;

	@InjectMocks
	ReportNotificationService reportNotificationService;

	@Test
	@DisplayName("notifyContributionReportResult: legacy reportId와 신규 contributionReportId를 함께 저장하고 발송한다")
	void notifyContributionReportResult_savesLegacyAndContributionIds() {
		Member reporter = member(10L);
		Store store = Store.builder()
			.name("크림빵집")
			.build();
		ReflectionTestUtils.setField(store, "id", 99L);
		given(fcmTokenRepository.findByMemberAndIsActive(reporter, true))
			.willReturn(List.of(fcmToken(reporter, "token-c")));

		reportNotificationService.notifyContributionReportResult(
			reporter,
			store,
			null,
			11L,
			101L,
			ReportKind.MENU_ADD_REPORT,
			ReportNotificationAction.APPROVED,
			"메뉴 추가");

		ArgumentCaptor<ReportNotification> notificationCaptor = ArgumentCaptor.forClass(ReportNotification.class);
		verify(reportNotificationRepository).save(notificationCaptor.capture());
		ReportNotification notification = notificationCaptor.getValue();
		assertThat(notification.getReportId()).isEqualTo(11L);
		assertThat(notification.getContributionReportId()).isEqualTo(101L);
		assertThat(notification.getAbuseReportId()).isNull();
		assertThat(notification.getReportKind()).isEqualTo(ReportKind.MENU_ADD_REPORT);
		assertThat(notification.getAction()).isEqualTo(ReportNotificationAction.APPROVED);

		@SuppressWarnings("unchecked") ArgumentCaptor<Map<String, String>> dataCaptor = ArgumentCaptor
			.forClass(Map.class);
		verify(fcmService).sendPushNotification(
			eq("token-c"),
			eq("제보가 반영되었어요"),
			eq("크림빵집의 메뉴 추가 제보가 승인되었어요 🎉"),
			dataCaptor.capture());
		assertThat(dataCaptor.getValue()).containsEntry("reportId", "11")
			.containsEntry("contributionReportId", "101")
			.containsEntry("reportKind", "MENU_ADD_REPORT")
			.containsEntry("storeId", "99");
	}

	@Test
	@DisplayName("notifyAbuseReportResult: 신고자에게 제재 정보 없이 검토 완료 알림을 저장하고 발송한다")
	void notifyAbuseReportResult_savesAndSendsResultWithoutModerationData() {
		Member reporter = member(10L);
		AbuseReport report = abuseReport(31L, reporter, member(20L));
		given(fcmTokenRepository.findByMemberAndIsActive(reporter, true))
			.willReturn(List.of(fcmToken(reporter, "token-a"), fcmToken(reporter, "token-a")));

		reportNotificationService.notifyAbuseReportResult(report);

		ArgumentCaptor<ReportNotification> notificationCaptor = ArgumentCaptor.forClass(ReportNotification.class);
		verify(reportNotificationRepository).save(notificationCaptor.capture());
		ReportNotification notification = notificationCaptor.getValue();
		assertThat(notification.getMember()).isEqualTo(reporter);
		assertThat(notification.getReportId()).isEqualTo(31L);
		assertThat(notification.getAbuseReportId()).isEqualTo(31L);
		assertThat(notification.getContributionReportId()).isNull();
		assertThat(notification.getModerationActionId()).isNull();
		assertThat(notification.getReportKind()).isEqualTo(ReportKind.ABUSE_REPORT);
		assertThat(notification.getAction()).isEqualTo(ReportNotificationAction.ABUSE_REPORT_RESULT);
		assertThat(notification.getTitle()).isEqualTo("신고 검토가 완료되었습니다.");
		assertThat(notification.getMessage()).isEqualTo("운영 정책에 따라 필요한 조치를 진행했습니다.");

		@SuppressWarnings("unchecked") ArgumentCaptor<Map<String, String>> dataCaptor = ArgumentCaptor
			.forClass(Map.class);
		verify(fcmService, times(1)).sendPushNotification(
			eq("token-a"),
			eq("신고 검토가 완료되었습니다."),
			eq("운영 정책에 따라 필요한 조치를 진행했습니다."),
			dataCaptor.capture());
		assertThat(dataCaptor.getValue()).containsEntry("type", "ABUSE_REPORT_RESULT")
			.containsEntry("reportId", "31")
			.containsEntry("abuseReportId", "31")
			.containsEntry("reportKind", "ABUSE_REPORT");
		assertThat(dataCaptor.getValue()).doesNotContainKeys("moderationActionId", "targetType", "reportedMemberId");
	}

	@Test
	@DisplayName("notifyModerationAction: 피신고자에게 moderationActionId를 포함한 조치 알림을 저장하고 발송한다")
	void notifyModerationAction_savesAndSendsActionNotification() {
		Member reporter = member(10L);
		Member reportedMember = member(20L);
		AbuseReport report = abuseReport(32L, reporter, reportedMember);
		ModerationAction action = ModerationAction.builder()
			.report(report)
			.targetType(ModerationTargetType.REVIEW)
			.targetId(123L)
			.member(reportedMember)
			.action(AbuseReportAction.HIDE_REVIEW)
			.reason("욕설 포함")
			.build();
		ReflectionTestUtils.setField(action, "id", 77L);
		given(fcmTokenRepository.findByMemberAndIsActive(reportedMember, true))
			.willReturn(List.of(fcmToken(reportedMember, "token-b")));

		reportNotificationService.notifyModerationAction(action);

		ArgumentCaptor<ReportNotification> notificationCaptor = ArgumentCaptor.forClass(ReportNotification.class);
		verify(reportNotificationRepository).save(notificationCaptor.capture());
		ReportNotification notification = notificationCaptor.getValue();
		assertThat(notification.getMember()).isEqualTo(reportedMember);
		assertThat(notification.getReportId()).isEqualTo(32L);
		assertThat(notification.getAbuseReportId()).isEqualTo(32L);
		assertThat(notification.getContributionReportId()).isNull();
		assertThat(notification.getModerationActionId()).isEqualTo(77L);
		assertThat(notification.getReportKind()).isEqualTo(ReportKind.ABUSE_REPORT);
		assertThat(notification.getAction()).isEqualTo(ReportNotificationAction.MODERATION_ACTION);
		assertThat(notification.getTitle()).isEqualTo("운영 정책 위반으로 콘텐츠가 제한되었습니다.");
		assertThat(notification.getMessage())
			.isEqualTo("결정에 동의하지 않는 경우 30일 이내에 이의제기를 요청할 수 있습니다.");

		@SuppressWarnings("unchecked") ArgumentCaptor<Map<String, String>> dataCaptor = ArgumentCaptor
			.forClass(Map.class);
		verify(fcmService).sendPushNotification(
			eq("token-b"),
			eq("운영 정책 위반으로 콘텐츠가 제한되었습니다."),
			eq("결정에 동의하지 않는 경우 30일 이내에 이의제기를 요청할 수 있습니다."),
			dataCaptor.capture());
		assertThat(dataCaptor.getValue()).containsEntry("type", "MODERATION_ACTION")
			.containsEntry("reportId", "32")
			.containsEntry("abuseReportId", "32")
			.containsEntry("reportKind", "ABUSE_REPORT")
			.containsEntry("moderationActionId", "77");
	}

	private AbuseReport abuseReport(Long id, Member reporter, Member reportedMember) {
		AbuseReport report = AbuseReport.builder()
			.reporter(reporter)
			.targetType(AbuseReportTargetType.REVIEW)
			.targetId(123L)
			.reportedMember(reportedMember)
			.reasonType(AbuseReportReasonType.ABUSE)
			.reasonDetail("욕설")
			.build();
		ReflectionTestUtils.setField(report, "id", id);
		return report;
	}

	private Member member(Long id) {
		Member member = Member.builder()
			.nickname("사용자")
			.build();
		ReflectionTestUtils.setField(member, "id", id);
		return member;
	}

	private FcmToken fcmToken(Member member, String token) {
		return FcmToken.builder()
			.member(member)
			.deviceId(token)
			.deviceOs(DeviceOs.IOS)
			.token(token)
			.build();
	}
}
