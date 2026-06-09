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

import sweetgirl.croissant.alarm.entity.FcmToken;
import sweetgirl.croissant.alarm.entity.ReportNotification;
import sweetgirl.croissant.alarm.enums.DeviceOs;
import sweetgirl.croissant.alarm.enums.ReportKind;
import sweetgirl.croissant.alarm.enums.ReportNotificationAction;
import sweetgirl.croissant.alarm.repository.FcmTokenRepository;
import sweetgirl.croissant.alarm.repository.ReportNotificationRepository;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.report.entity.ModerationAction;
import sweetgirl.croissant.report.entity.UserReport;
import sweetgirl.croissant.report.enums.ModerationTargetType;
import sweetgirl.croissant.report.enums.ReportReasonType;
import sweetgirl.croissant.report.enums.ReportTargetType;
import sweetgirl.croissant.report.enums.UserReportAction;

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
	@DisplayName("notifyUserReportResult: 신고자에게 제재 정보 없이 검토 완료 알림을 저장하고 발송한다")
	void notifyUserReportResult_savesAndSendsResultWithoutModerationData() {
		Member reporter = member(10L);
		UserReport report = userReport(31L, reporter, member(20L));
		given(fcmTokenRepository.findByMemberAndIsActive(reporter, true))
			.willReturn(List.of(fcmToken(reporter, "token-a"), fcmToken(reporter, "token-a")));

		reportNotificationService.notifyUserReportResult(report);

		ArgumentCaptor<ReportNotification> notificationCaptor = ArgumentCaptor.forClass(ReportNotification.class);
		verify(reportNotificationRepository).save(notificationCaptor.capture());
		ReportNotification notification = notificationCaptor.getValue();
		assertThat(notification.getMember()).isEqualTo(reporter);
		assertThat(notification.getReportId()).isEqualTo(31L);
		assertThat(notification.getModerationActionId()).isNull();
		assertThat(notification.getReportKind()).isEqualTo(ReportKind.USER_REPORT);
		assertThat(notification.getAction()).isEqualTo(ReportNotificationAction.USER_REPORT_RESULT);
		assertThat(notification.getTitle()).isEqualTo("신고 검토가 완료되었습니다.");
		assertThat(notification.getMessage()).isEqualTo("운영 정책에 따라 필요한 조치를 진행했습니다.");

		@SuppressWarnings("unchecked") ArgumentCaptor<Map<String, String>> dataCaptor = ArgumentCaptor
			.forClass(Map.class);
		verify(fcmService, times(1)).sendPushNotification(
			eq("token-a"),
			eq("신고 검토가 완료되었습니다."),
			eq("운영 정책에 따라 필요한 조치를 진행했습니다."),
			dataCaptor.capture());
		assertThat(dataCaptor.getValue()).containsEntry("type", "USER_REPORT_RESULT")
			.containsEntry("reportId", "31")
			.containsEntry("reportKind", "USER_REPORT");
		assertThat(dataCaptor.getValue()).doesNotContainKeys("moderationActionId", "targetType", "reportedMemberId");
	}

	@Test
	@DisplayName("notifyModerationAction: 피신고자에게 moderationActionId를 포함한 조치 알림을 저장하고 발송한다")
	void notifyModerationAction_savesAndSendsActionNotification() {
		Member reporter = member(10L);
		Member reportedMember = member(20L);
		UserReport report = userReport(32L, reporter, reportedMember);
		ModerationAction action = ModerationAction.builder()
			.report(report)
			.targetType(ModerationTargetType.REVIEW)
			.targetId(123L)
			.member(reportedMember)
			.action(UserReportAction.HIDE_REVIEW)
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
		assertThat(notification.getModerationActionId()).isEqualTo(77L);
		assertThat(notification.getReportKind()).isEqualTo(ReportKind.USER_REPORT);
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
			.containsEntry("reportKind", "USER_REPORT")
			.containsEntry("moderationActionId", "77");
	}

	private UserReport userReport(Long id, Member reporter, Member reportedMember) {
		UserReport report = UserReport.builder()
			.reporter(reporter)
			.targetType(ReportTargetType.REVIEW)
			.targetId(123L)
			.reportedMember(reportedMember)
			.reasonType(ReportReasonType.ABUSE)
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
