package sweetgirl.croissant.alarm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.alarm.code.AlarmErrorCode;
import sweetgirl.croissant.alarm.entity.FcmToken;
import sweetgirl.croissant.alarm.entity.Notification;
import sweetgirl.croissant.alarm.enums.NotificationChannel;
import sweetgirl.croissant.alarm.enums.NotificationType;
import sweetgirl.croissant.alarm.exception.AlarmException;
import sweetgirl.croissant.alarm.repository.FcmTokenRepository;
import sweetgirl.croissant.alarm.repository.NotificationDeliveryRepository;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.entity.NotificationSetting;
import sweetgirl.croissant.member.repository.NotificationSettingRepository;

@ExtendWith(MockitoExtension.class)
class PushDispatcherTest {

	private static final LocalDateTime DAY = LocalDateTime.of(2026, 9, 25, 14, 0);
	private static final LocalDateTime NIGHT_START = LocalDateTime.of(2026, 9, 25, 21, 0);
	private static final LocalDateTime DAWN = LocalDateTime.of(2026, 9, 26, 7, 59);
	private static final LocalDateTime MORNING = LocalDateTime.of(2026, 9, 26, 8, 0);

	@Mock
	NotificationSettingRepository notificationSettingRepository;
	@Mock
	FcmTokenRepository fcmTokenRepository;
	@Mock
	NotificationDeliveryRepository deliveryRepository;
	@Mock
	FcmService fcmService;

	PushDispatcher pushDispatcher;

	@BeforeEach
	void setUpDispatcher() {
		pushDispatcher = new PushDispatcher(notificationSettingRepository, fcmTokenRepository,
			new NotificationDeliveryCommandService(deliveryRepository), fcmService);
	}

	@Test
	@DisplayName("야간(21시)에는 야간 동의가 없는 사람에게 광고를 보내지 않는다")
	void ad_night_withoutNightConsent_skips() {
		Member member = marketingMember(false);
		givenSetting(member);

		pushDispatcher.dispatch(ad(), member, NIGHT_START);

		verify(fcmService, never()).sendPushNotification(anyString(), anyString(), anyString(), anyMap());
	}

	@Test
	@DisplayName("새벽(07:59)도 야간이다")
	void ad_dawn_withoutNightConsent_skips() {
		Member member = marketingMember(false);
		givenSetting(member);

		pushDispatcher.dispatch(ad(), member, DAWN);

		verify(fcmService, never()).sendPushNotification(anyString(), anyString(), anyString(), anyMap());
	}

	@Test
	@DisplayName("야간이라도 야간 동의가 있으면 광고를 보낸다")
	void ad_night_withNightConsent_sends() {
		Member member = marketingMember(true);
		givenSetting(member);
		givenToken(member);

		pushDispatcher.dispatch(ad(), member, NIGHT_START);

		verify(fcmService).sendPushNotification(eq("token"), anyString(), anyString(), anyMap());
	}

	@Test
	@DisplayName("낮(14시)·08시 정각에는 야간 동의 없이도 광고를 보낸다")
	void ad_day_withoutNightConsent_sends() {
		Member member = marketingMember(false);
		givenSetting(member);
		givenToken(member);

		pushDispatcher.dispatch(ad(), member, DAY);
		pushDispatcher.dispatch(ad(), member, MORNING);

		verify(fcmService, times(2))
			.sendPushNotification(eq("token"), anyString(), anyString(), anyMap());
	}

	@Test
	@DisplayName("광고가 아닌 알림은 야간에도 야간 동의 없이 보낸다")
	void notice_night_withoutNightConsent_sends() {
		Member member = marketingMember(false);
		givenSetting(member);
		givenToken(member);

		pushDispatcher.dispatch(notification(NotificationType.NOTICE), member, NIGHT_START);

		verify(fcmService).sendPushNotification(eq("token"), anyString(), anyString(), anyMap());
	}

	@Test
	void monthlyReportNoticeSendsDirectLinkWithoutMarketingConsent() {
		Member member = new Member("테스터");
		given(notificationSettingRepository.findByMemberId(any())).willReturn(Optional.of(
			NotificationSetting.builder().member(member).isNotificationEnabled(true).isMarketingEnabled(false)
				.build()));
		givenToken(member);
		Notification report = Notification.announcement(NotificationType.NOTICE, NotificationChannel.PUSH,
			"9월 빵일지가 도착했어요", "빵 기록을 확인해 보세요.", "/reports/monthly/2026-09");

		pushDispatcher.dispatch(report, member, DAY);

		@SuppressWarnings("unchecked") ArgumentCaptor<Map<String, String>> data = ArgumentCaptor.forClass(Map.class);
		verify(fcmService).sendPushNotification(eq("token"), eq(report.getTitle()), eq(report.getBody()),
			data.capture());
		assertThat(data.getValue()).containsEntry("link", "/reports/monthly/2026-09").containsEntry("type", "NOTICE");
	}

	@Test
	void monthlyReportNoticeRespectsNotificationOptOut() {
		Member member = new Member("테스터");
		given(notificationSettingRepository.findByMemberId(any())).willReturn(Optional.of(
			NotificationSetting.builder().member(member).isNotificationEnabled(false).isMarketingEnabled(true)
				.build()));
		pushDispatcher.dispatch(Notification.announcement(NotificationType.NOTICE, NotificationChannel.PUSH,
			"9월 빵일지가 도착했어요", "빵 기록을 확인해 보세요.", "/reports/monthly/2026-09"), member, DAY);
		verify(fcmTokenRepository, never()).findByMemberAndIsActive(any(), any(Boolean.class));
		verify(fcmService, never()).sendPushNotification(anyString(), anyString(), anyString(), anyMap());
	}

	@Test
	void invalidTokenAndTransientFailureCannotCountAsSuccess() {
		Member member = marketingMember(false);
		givenSetting(member);
		FcmToken invalid = mock(FcmToken.class);
		FcmToken temporary = mock(FcmToken.class);
		given(invalid.getToken()).willReturn("invalid");
		given(temporary.getToken()).willReturn("temporary");
		given(fcmTokenRepository.findByMemberAndIsActive(member, true)).willReturn(List.of(invalid, temporary));
		given(fcmService.sendPushNotification(eq("invalid"), any(), any(), anyMap()))
			.willReturn(FcmService.SendResult.INVALID_TOKEN);
		given(fcmService.sendPushNotification(eq("temporary"), any(), any(), anyMap()))
			.willThrow(new AlarmException(
				AlarmErrorCode.FCM_SEND_FAILED));
		assertThat(pushDispatcher.dispatchWithResult(notification(NotificationType.NOTICE), member, DAY))
			.isEqualTo(PushDispatcher.Result.FAILED);
		verify(deliveryRepository).recordPushResult(any(), any(), eq("FAILED"), eq("FCM_SEND_FAILED"), eq(DAY));
	}

	@Test
	void onlyInvalidTokensAreSkipped() {
		Member member = marketingMember(false);
		givenSetting(member);
		givenToken(member, FcmService.SendResult.INVALID_TOKEN);
		assertThat(pushDispatcher.dispatchWithResult(notification(NotificationType.NOTICE), member, DAY))
			.isEqualTo(PushDispatcher.Result.SKIPPED);
		verify(deliveryRepository, never()).recordPushResult(any(), any(), any(), any(), any());
	}

	private Member marketingMember(boolean nightAgreed) {
		Member member = Member.builder().nickname("테스터").build();
		member.updateMarketingAgreement(true);
		member.updateNightMarketingAgreement(nightAgreed);
		return member;
	}

	private void givenSetting(Member member) {
		NotificationSetting setting = NotificationSetting.builder()
			.member(member).isNotificationEnabled(true).isMarketingEnabled(true).build();
		given(notificationSettingRepository.findByMemberId(any())).willReturn(Optional.of(setting));
	}

	private void givenToken(Member member) {
		givenToken(member, FcmService.SendResult.SENT);
	}

	private void givenToken(Member member, FcmService.SendResult result) {
		FcmToken token = mock(FcmToken.class);
		given(token.getToken()).willReturn("token");
		given(fcmTokenRepository.findByMemberAndIsActive(member, true)).willReturn(List.of(token));
		given(fcmService.sendPushNotification(eq("token"), any(), any(), anyMap())).willReturn(result);
	}

	private Notification ad() {
		return notification(NotificationType.AD);
	}

	private Notification notification(NotificationType type) {
		return Notification.announcement(type, NotificationChannel.PUSH, "제목", "본문", null);
	}
}
