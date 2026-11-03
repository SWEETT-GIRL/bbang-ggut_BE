package sweetgirl.croissant.alarm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

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
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.alarm.entity.FcmToken;
import sweetgirl.croissant.alarm.enums.DeviceOs;
import sweetgirl.croissant.alarm.entity.Notification;
import sweetgirl.croissant.alarm.enums.NotificationTargetType;
import sweetgirl.croissant.alarm.enums.NotificationType;
import sweetgirl.croissant.alarm.repository.FcmTokenRepository;
import sweetgirl.croissant.alarm.repository.NotificationDeliveryRepository;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.entity.NotificationSetting;
import sweetgirl.croissant.member.repository.NotificationSettingRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("PushDispatcher — 커뮤니티 알림 스위치 · 묶음 푸시")
class PushDispatcherCommunityTest {

	@Mock
	private NotificationSettingRepository notificationSettingRepository;
	@Mock
	private FcmTokenRepository fcmTokenRepository;
	@Mock
	private NotificationDeliveryRepository deliveryRepository;
	@Mock
	private FcmService fcmService;

	private PushDispatcher pushDispatcher;
	private Member member;

	@BeforeEach
	void setUp() {
		pushDispatcher = new PushDispatcher(notificationSettingRepository, fcmTokenRepository,
			new NotificationDeliveryCommandService(deliveryRepository), fcmService);
		member = Member.builder().nickname("받는 사람").build();
		ReflectionTestUtils.setField(member, "id", 2L);
	}

	private Notification notification(NotificationType type) {
		Member actor = Member.builder().nickname("보낸 사람").build();
		ReflectionTestUtils.setField(actor, "id", 1L);
		return Notification.communityActivity(type, actor, NotificationTargetType.RECORD, 10L, "밀도", "제목", null, null);
	}

	private NotificationSetting setting() {
		return NotificationSetting.builder().member(member).isNotificationEnabled(true).build();
	}

	@Test
	@DisplayName("좋아요 스위치를 끄면 좋아요 푸시는 SKIPPED — 알림함엔 남는다")
	void switchOff() {
		NotificationSetting setting = setting();
		setting.updateCommunitySwitches(null, null, false, null, null);
		given(notificationSettingRepository.findByMemberId(2L)).willReturn(Optional.of(setting));

		PushDispatcher.Result result = pushDispatcher.dispatchWithResult(notification(NotificationType.RECORD_LIKE),
			member, LocalDateTime.now());

		assertThat(result).isEqualTo(PushDispatcher.Result.SKIPPED);
		then(fcmTokenRepository).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("묶음 푸시도 종류 스위치를 본다 — 좋아요를 끄면 좋아요 묶음은 SKIPPED")
	void digestRespectsSwitch() {
		NotificationSetting setting = setting();
		setting.updateCommunitySwitches(null, null, false, null, null);
		given(notificationSettingRepository.findByMemberId(2L)).willReturn(Optional.of(setting));
		List<Notification> group = List.of(notification(NotificationType.RECORD_LIKE),
			notification(NotificationType.RECORD_LIKE));

		PushDispatcher.Result result = pushDispatcher.dispatchDigest(group, member,
			PushDigest.of(NotificationType.RECORD_LIKE, group), LocalDateTime.now());

		assertThat(result).isEqualTo(PushDispatcher.Result.SKIPPED);
		then(fcmTokenRepository).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("묶음 푸시는 접은 문구 · 링크로 한 번 보내고, 대장에는 묶인 알림마다 한 줄씩 적는다")
	void digestSendsOnceAndRecordsEvery() {
		given(notificationSettingRepository.findByMemberId(2L)).willReturn(Optional.of(setting()));
		FcmToken token = FcmToken.builder().member(member).deviceId("d").deviceOs(DeviceOs.IOS).token("tok").build();
		given(fcmTokenRepository.findByMemberAndIsActive(member, true)).willReturn(List.of(token));
		given(fcmService.sendPushNotification(eq("tok"), anyString(), anyString(), anyMap()))
			.willReturn(FcmService.SendResult.SENT);
		Notification first = notification(NotificationType.FOLLOWING_NEW_RECORD);
		Notification second = notification(NotificationType.FOLLOWING_NEW_RECORD);
		ReflectionTestUtils.setField(first, "id", 100L);
		ReflectionTestUtils.setField(second, "id", 101L);
		List<Notification> group = List.of(first, second);
		LocalDateTime now = LocalDateTime.now();

		PushDispatcher.Result result = pushDispatcher.dispatchDigest(group, member,
			new PushDigest("접은 제목", "접은 본문", "/profile/1", second), now);

		assertThat(result).isEqualTo(PushDispatcher.Result.SENT);
		ArgumentCaptor<Map<String, String>> data = ArgumentCaptor.forClass(Map.class);
		then(fcmService).should().sendPushNotification(eq("tok"), eq("접은 제목"), eq("접은 본문"), data.capture());
		assertThat(data.getValue()).containsEntry("link", "/profile/1").containsEntry("notificationId", "101");
		then(deliveryRepository).should().recordPushResult(100L, 2L, "SENT", null, now);
		then(deliveryRepository).should().recordPushResult(101L, 2L, "SENT", null, now);
	}
}
