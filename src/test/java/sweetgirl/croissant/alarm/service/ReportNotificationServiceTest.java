package sweetgirl.croissant.alarm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.abuse.entity.AbuseReport;
import sweetgirl.croissant.abuse.entity.ModerationAction;
import sweetgirl.croissant.abuse.enums.AbuseReportAction;
import sweetgirl.croissant.abuse.enums.AbuseReportReasonType;
import sweetgirl.croissant.abuse.enums.AbuseReportTargetType;
import sweetgirl.croissant.abuse.enums.ModerationTargetType;
import sweetgirl.croissant.alarm.entity.Notification;
import sweetgirl.croissant.alarm.enums.NotificationTargetType;
import sweetgirl.croissant.alarm.enums.NotificationType;
import sweetgirl.croissant.alarm.enums.ReportNotificationAction;
import sweetgirl.croissant.alarm.event.PushRequestedEvent;
import sweetgirl.croissant.alarm.repository.NotificationRecipientRepository;
import sweetgirl.croissant.common.enums.ReportKind;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.store.entity.Store;

/**
 * 제보 알림이 통합 알림함에 어떤 모양으로 앉는지를 본다.
 *
 * <p>푸시가 실제로 어떻게 나가는지는 여기서 확인하지 않는다 — 이 서비스는 이벤트를 낼 뿐이고,
 * 그 뒤는 {@link PushDispatcher}의 몫이라 그쪽 테스트에서 본다.
 */
@ExtendWith(MockitoExtension.class)
class ReportNotificationServiceTest {

	@Mock
	NotificationRecipientRepository recipientRepository;
	@Mock
	NotificationRecorder notificationRecorder;
	@Mock
	ApplicationEventPublisher eventPublisher;

	@InjectMocks
	ReportNotificationService reportNotificationService;

	@Test
	@DisplayName("기여 제보 결과: reportId와 contributionReportId를 payload에 함께 담고 가게를 대상으로 가리킨다")
	void notifyContributionReportResult_keepsBothIdsInPayload() {
		Member reporter = member(10L);
		Store store = store(99L, "크림빵집");
		givenRecorded(500L);

		reportNotificationService.notifyContributionReportResult(
			reporter, store, null, 11L, 101L,
			ReportKind.MENU_ADD_REPORT, ReportNotificationAction.APPROVED, "메뉴 추가");

		Notification notification = capturedNotification(reporter);
		assertThat(notification.getType()).isEqualTo(NotificationType.CONTRIBUTION_RESULT);
		assertThat(notification.getTitle()).isEqualTo("제보가 반영되었어요");
		assertThat(notification.getBody()).isEqualTo("크림빵집의 메뉴 추가 제보가 승인되었어요 🎉");
		assertThat(notification.getTargetType()).isEqualTo(NotificationTargetType.STORE);
		assertThat(notification.getTargetId()).isEqualTo(99L);
		assertThat(notification.getTargetTitle()).isEqualTo("크림빵집");
		assertThat(notification.getPayload())
			.containsEntry("reportId", "11")
			.containsEntry("contributionReportId", "101")
			.containsEntry("reportKind", "MENU_ADD_REPORT")
			.containsEntry("action", "APPROVED")
			.doesNotContainKey("abuseReportId");

		verify(eventPublisher).publishEvent(PushRequestedEvent.to(500L, 10L));
	}

	@Test
	@DisplayName("신고 결과: 신고자에게 가고 제재 정보는 담기지 않는다")
	void notifyAbuseReportResult_hasNoModerationData() {
		Member reporter = member(10L);
		AbuseReport report = abuseReport(31L, reporter, member(20L));
		givenRecorded(501L);

		reportNotificationService.notifyAbuseReportResult(report);

		Notification notification = capturedNotification(reporter);
		assertThat(notification.getType()).isEqualTo(NotificationType.ABUSE_REPORT_RESULT);
		assertThat(notification.getTitle()).isEqualTo("신고 검토가 완료되었습니다.");
		assertThat(notification.getBody()).isEqualTo("운영 정책에 따라 필요한 조치를 진행했습니다.");
		assertThat(notification.getTargetType()).isNull();
		assertThat(notification.getPayload())
			.containsEntry("reportId", "31")
			.containsEntry("abuseReportId", "31")
			.containsEntry("reportKind", "ABUSE_REPORT")
			.doesNotContainKeys("moderationActionId", "contributionReportId");

		verify(eventPublisher).publishEvent(PushRequestedEvent.to(501L, 10L));
	}

	@Test
	@DisplayName("제재 알림: 신고자가 아니라 피조치 회원에게 가고 moderationActionId를 담는다")
	void notifyModerationAction_goesToModeratedMember() {
		Member reporter = member(10L);
		Member reportedMember = member(20L);
		ModerationAction action = moderationAction(77L, abuseReport(32L, reporter, reportedMember),
			reportedMember);
		givenRecorded(502L);

		reportNotificationService.notifyModerationAction(action);

		Notification notification = capturedNotification(reportedMember);
		assertThat(notification.getType()).isEqualTo(NotificationType.MODERATION_ACTION);
		assertThat(notification.getPayload())
			.containsEntry("reportId", "32")
			.containsEntry("abuseReportId", "32")
			.containsEntry("moderationActionId", "77")
			.containsEntry("action", "MODERATION_ACTION");

		// 앱이 이의제기 화면을 여는 값이라 이것이 빠지면 알림이 눌리지 않는다.
		verify(eventPublisher).publishEvent(PushRequestedEvent.to(502L, 20L));
	}

	@Test
	@DisplayName("제보자가 없으면(익명 제보) 아무것도 쌓지 않는다")
	void notifyContributionReportResult_skipsWhenNoReporter() {
		reportNotificationService.notifyContributionReportResult(
			null, store(99L, "크림빵집"), null, 11L, 101L,
			ReportKind.MENU_ADD_REPORT, ReportNotificationAction.APPROVED, "메뉴 추가");

		verify(notificationRecorder, org.mockito.Mockito.never())
			.recordFor(any(), any(), any());
		verify(eventPublisher, org.mockito.Mockito.never()).publishEvent(any(Object.class));
	}

	@Test
	@DisplayName("가게가 없으면 fallback 이름으로 문구를 만들고 대상은 비운다")
	void notifyContributionReportResult_usesFallbackStoreName() {
		Member reporter = member(10L);
		givenRecorded(503L);

		reportNotificationService.notifyContributionReportResult(
			reporter, null, "새로 생긴 빵집", 11L, 101L,
			ReportKind.STORE_ADD_REPORT, ReportNotificationAction.REJECTED, "가게 추가");

		Notification notification = capturedNotification(reporter);
		assertThat(notification.getBody()).isEqualTo("새로 생긴 빵집의 가게 추가 제보가 반려되었어요");
		assertThat(notification.getTargetType()).isNull();
		assertThat(notification.getTargetId()).isNull();
		assertThat(notification.getTargetTitle()).isEqualTo("새로 생긴 빵집");
	}

	// ── 도우미 ──────────────────────────────────────────────────────────────

	/** 저장된 알림에 id가 박혀 돌아오는 실제 동작을 흉내낸다 — 그 id로 푸시 이벤트가 나간다. */
	private void givenRecorded(Long notificationId) {
		given(notificationRecorder.recordFor(any(), any(), any()))
			.willAnswer(invocation -> {
				Notification notification = invocation.getArgument(0);
				ReflectionTestUtils.setField(notification, "id", notificationId);
				return notification;
			});
	}

	private Notification capturedNotification(Member expectedRecipient) {
		ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
		verify(notificationRecorder).recordFor(captor.capture(), eq(expectedRecipient), any());
		return captor.getValue();
	}

	private ModerationAction moderationAction(Long id, AbuseReport report, Member member) {
		ModerationAction action = ModerationAction.builder()
			.report(report)
			.targetType(ModerationTargetType.REVIEW)
			.targetId(123L)
			.member(member)
			.action(AbuseReportAction.HIDE_REVIEW)
			.reason("욕설 포함")
			.build();
		ReflectionTestUtils.setField(action, "id", id);
		return action;
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

	private Store store(Long id, String name) {
		Store store = Store.builder().name(name).build();
		ReflectionTestUtils.setField(store, "id", id);
		return store;
	}

	private Member member(Long id) {
		Member member = Member.builder().nickname("사용자").build();
		ReflectionTestUtils.setField(member, "id", id);
		return member;
	}
}
