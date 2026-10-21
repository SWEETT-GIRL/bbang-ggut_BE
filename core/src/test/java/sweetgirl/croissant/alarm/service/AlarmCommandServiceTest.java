package sweetgirl.croissant.alarm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import sweetgirl.croissant.alarm.code.AlarmErrorCode;
import sweetgirl.croissant.alarm.dto.request.GeofenceEnterRequest;
import sweetgirl.croissant.alarm.dto.response.MarketingEnabledResponse;
import sweetgirl.croissant.alarm.entity.FcmToken;
import sweetgirl.croissant.alarm.exception.AlarmException;
import sweetgirl.croissant.alarm.repository.FcmTokenRepository;
import sweetgirl.croissant.alarm.repository.StoreNotificationRepository;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.entity.NotificationSetting;
import sweetgirl.croissant.member.enums.NotificationRadius;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.store.code.StoreErrorCode;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.exception.StoreException;
import sweetgirl.croissant.store.repository.StoreRepository;
import sweetgirl.croissant.store.repository.SubscribedStoreRepository;

@ExtendWith(MockitoExtension.class)
class AlarmCommandServiceTest {

	@Mock
	StoreNotificationRepository storeNotificationRepository;
	@Mock
	FcmTokenRepository fcmTokenRepository;
	@Mock
	FcmService fcmService;
	@Mock
	SubscribedStoreRepository subscribedStoreRepository;
	@Mock
	MemberReader memberReader;
	@Mock
	StoreRepository storeRepository;
	/** 푸시 발송 건수를 세는 곳 — 옛 PostHog 이벤트를 메트릭으로 옮겼다 */
	@Spy
	SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
	@Mock
	NotificationSettingProvider notificationSettingProvider;

	@InjectMocks
	AlarmCommandService alarmCommandService;

	// ───────────────────────── handleGeofenceEnter ─────────────────────────

	@Test
	@DisplayName("handleGeofenceEnter: 알림 OFF이면 FCM 발송 없이 종료한다")
	void handleGeofenceEnter_notificationDisabled_silentReturn() {
		Long memberId = 1L;
		NotificationSetting setting = mock(NotificationSetting.class);
		GeofenceEnterRequest request = mock(GeofenceEnterRequest.class);

		given(notificationSettingProvider.getOrCreate(memberId)).willReturn(setting);
		given(setting.isNotificationEnabled()).willReturn(false);

		alarmCommandService.handleGeofenceEnter(memberId, request);

		verify(storeNotificationRepository, never()).save(any());
	}

	@Test
	@DisplayName("handleGeofenceEnter: storeId에 해당하는 가게가 없으면 StoreException을 던진다")
	void handleGeofenceEnter_storeNotFound_throwsException() {
		Long memberId = 1L;
		NotificationSetting setting = mock(NotificationSetting.class);
		GeofenceEnterRequest request = mock(GeofenceEnterRequest.class);

		given(notificationSettingProvider.getOrCreate(memberId)).willReturn(setting);
		given(setting.isNotificationEnabled()).willReturn(true);
		given(setting.getStartTime()).willReturn(LocalTime.of(0, 0));
		given(setting.getEndTime()).willReturn(LocalTime.of(23, 59));
		given(setting.getDaysOfWeek()).willReturn("MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY,SATURDAY,SUNDAY");
		given(request.getStoreId()).willReturn(99L);
		given(storeRepository.findById(99L)).willReturn(Optional.empty());

		assertThatThrownBy(() -> alarmCommandService.handleGeofenceEnter(memberId, request))
			.isInstanceOf(StoreException.class)
			.hasMessageContaining(StoreErrorCode.STORE_NOT_FOUND.getMessage());
	}

	@Test
	@DisplayName("handleGeofenceEnter: 구독하지 않은 가게 또는 반경 밖이면 GEOFENCE_NOT_SUBSCRIBED를 던진다")
	void handleGeofenceEnter_notSubscribedOrOutOfRadius_throwsException() {
		Long memberId = 1L;
		Member member = mock(Member.class);
		Store store = mock(Store.class);
		NotificationSetting setting = mock(NotificationSetting.class);
		GeofenceEnterRequest request = mock(GeofenceEnterRequest.class);

		given(memberReader.getMember(memberId)).willReturn(member);
		given(notificationSettingProvider.getOrCreate(memberId)).willReturn(setting);
		given(setting.isNotificationEnabled()).willReturn(true);
		given(setting.getStartTime()).willReturn(LocalTime.of(0, 0));
		given(setting.getEndTime()).willReturn(LocalTime.of(23, 59));
		given(setting.getDaysOfWeek()).willReturn("MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY,SATURDAY,SUNDAY");
		given(setting.getNotificationRadius()).willReturn(NotificationRadius.R300);
		given(request.getStoreId()).willReturn(1L);
		given(request.getLatitude()).willReturn(37.5);
		given(request.getLongitude()).willReturn(127.0);
		given(storeRepository.findById(1L)).willReturn(Optional.of(store));
		given(subscribedStoreRepository.existsSubscribedStoreWithinRadius(memberId, 1L, 37.5, 127.0, 300))
			.willReturn(false);

		assertThatThrownBy(() -> alarmCommandService.handleGeofenceEnter(memberId, request))
			.isInstanceOf(AlarmException.class)
			.hasMessageContaining(AlarmErrorCode.GEOFENCE_NOT_SUBSCRIBED.getMessage());
	}

	@Test
	@DisplayName("handleGeofenceEnter: 오늘 이미 알림을 보낸 가게면 FCM 발송 없이 종료한다")
	void handleGeofenceEnter_alreadyNotifiedToday_silentReturn() {
		Long memberId = 1L;
		Member member = mock(Member.class);
		Store store = mock(Store.class);
		given(store.getId()).willReturn(1L);
		NotificationSetting setting = mock(NotificationSetting.class);
		GeofenceEnterRequest request = mock(GeofenceEnterRequest.class);

		given(memberReader.getMember(memberId)).willReturn(member);
		given(notificationSettingProvider.getOrCreate(memberId)).willReturn(setting);
		given(setting.isNotificationEnabled()).willReturn(true);
		given(setting.getStartTime()).willReturn(LocalTime.of(0, 0));
		given(setting.getEndTime()).willReturn(LocalTime.of(23, 59));
		given(setting.getDaysOfWeek()).willReturn("MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY,SATURDAY,SUNDAY");
		given(setting.getNotificationRadius()).willReturn(NotificationRadius.R300);
		given(request.getStoreId()).willReturn(1L);
		given(request.getLatitude()).willReturn(37.5);
		given(request.getLongitude()).willReturn(127.0);
		given(storeRepository.findById(1L)).willReturn(Optional.of(store));
		given(subscribedStoreRepository.existsSubscribedStoreWithinRadius(memberId, 1L, 37.5, 127.0, 300))
			.willReturn(true);
		given(storeNotificationRepository.tryAdvisoryXactLock(4294967297L)).willReturn(true);
		given(storeNotificationRepository.existsByMemberAndStoreAndSentAtAfter(eq(member), eq(store), any()))
			.willReturn(true);

		alarmCommandService.handleGeofenceEnter(memberId, request);

		verify(fcmTokenRepository, never()).findByMemberAndIsActive(any(), anyBoolean());
		verify(storeNotificationRepository, never()).save(any());
	}

	@Test
	@DisplayName("handleGeofenceEnter: FCM 전부 실패하면 StoreNotification을 저장하지 않는다")
	void handleGeofenceEnter_allFcmFail_doesNotSave() {
		Long memberId = 1L;
		Member member = mock(Member.class);
		given(member.getId()).willReturn(memberId);
		Store store = mock(Store.class);
		given(store.getId()).willReturn(1L);
		given(store.getName()).willReturn("뚜레쥬르");
		FcmToken token = mock(FcmToken.class);
		given(token.getToken()).willReturn("token-a");
		NotificationSetting setting = mock(NotificationSetting.class);
		GeofenceEnterRequest request = mock(GeofenceEnterRequest.class);

		given(memberReader.getMember(memberId)).willReturn(member);
		given(notificationSettingProvider.getOrCreate(memberId)).willReturn(setting);
		given(setting.isNotificationEnabled()).willReturn(true);
		given(setting.getStartTime()).willReturn(LocalTime.MIN);
		given(setting.getEndTime()).willReturn(LocalTime.MAX);
		given(setting.getDaysOfWeek()).willReturn("MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY,SATURDAY,SUNDAY");
		given(setting.getNotificationRadius()).willReturn(NotificationRadius.R300);
		given(request.getStoreId()).willReturn(1L);
		given(request.getLatitude()).willReturn(37.5);
		given(request.getLongitude()).willReturn(127.0);
		given(storeRepository.findById(1L)).willReturn(Optional.of(store));
		given(subscribedStoreRepository.existsSubscribedStoreWithinRadius(memberId, 1L, 37.5, 127.0, 300))
			.willReturn(true);
		given(storeNotificationRepository.tryAdvisoryXactLock(4294967297L)).willReturn(true);
		given(storeNotificationRepository.existsByMemberAndStoreAndSentAtAfter(eq(member), eq(store), any()))
			.willReturn(false);
		given(fcmTokenRepository.findByMemberAndIsActive(member, true)).willReturn(List.of(token));
		doThrow(new AlarmException(AlarmErrorCode.FCM_SEND_FAILED))
			.when(fcmService).sendPushNotification(any(), any(), any(), any());

		alarmCommandService.handleGeofenceEnter(memberId, request);

		verify(storeNotificationRepository, never()).save(any());
	}

	@Test
	@DisplayName("handleGeofenceEnter: 동일 token이 여러 row에 있어도 한 번만 발송한다")
	void handleGeofenceEnter_duplicateTokens_sendsOnce() {
		Long memberId = 1L;
		Member member = mock(Member.class);
		Store store = mock(Store.class);
		given(store.getId()).willReturn(1L);
		given(store.getName()).willReturn("뚜레쥬르");
		FcmToken tokenRow1 = mock(FcmToken.class);
		given(tokenRow1.getToken()).willReturn("dup-token");
		FcmToken tokenRow2 = mock(FcmToken.class);
		given(tokenRow2.getToken()).willReturn("dup-token");
		NotificationSetting setting = mock(NotificationSetting.class);
		GeofenceEnterRequest request = mock(GeofenceEnterRequest.class);

		given(memberReader.getMember(memberId)).willReturn(member);
		given(notificationSettingProvider.getOrCreate(memberId)).willReturn(setting);
		given(setting.isNotificationEnabled()).willReturn(true);
		given(setting.getStartTime()).willReturn(LocalTime.of(0, 0));
		given(setting.getEndTime()).willReturn(LocalTime.of(23, 59));
		given(setting.getDaysOfWeek()).willReturn("MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY,SATURDAY,SUNDAY");
		given(setting.getNotificationRadius()).willReturn(NotificationRadius.R300);
		given(request.getStoreId()).willReturn(1L);
		given(request.getLatitude()).willReturn(37.5);
		given(request.getLongitude()).willReturn(127.0);
		given(storeRepository.findById(1L)).willReturn(Optional.of(store));
		given(subscribedStoreRepository.existsSubscribedStoreWithinRadius(memberId, 1L, 37.5, 127.0, 300))
			.willReturn(true);
		given(storeNotificationRepository.tryAdvisoryXactLock(4294967297L)).willReturn(true);
		given(storeNotificationRepository.existsByMemberAndStoreAndSentAtAfter(eq(member), eq(store), any()))
			.willReturn(false);
		given(fcmTokenRepository.findByMemberAndIsActive(member, true)).willReturn(List.of(tokenRow1, tokenRow2));

		alarmCommandService.handleGeofenceEnter(memberId, request);

		verify(fcmService, times(1)).sendPushNotification(eq("dup-token"), any(), any(), any());
	}

	@Test
	@DisplayName("handleGeofenceEnter: 하나라도 FCM 성공하면 StoreNotification을 저장한다")
	void handleGeofenceEnter_anyFcmSuccess_savesNotification() {
		Long memberId = 1L;
		Member member = mock(Member.class);
		given(member.getId()).willReturn(memberId);
		Store store = mock(Store.class);
		given(store.getId()).willReturn(1L);
		given(store.getName()).willReturn("뚜레쥬르");
		FcmToken tokenA = mock(FcmToken.class);
		given(tokenA.getToken()).willReturn("token-a");
		FcmToken tokenB = mock(FcmToken.class);
		given(tokenB.getToken()).willReturn("token-b");
		NotificationSetting setting = mock(NotificationSetting.class);
		GeofenceEnterRequest request = mock(GeofenceEnterRequest.class);

		given(memberReader.getMember(memberId)).willReturn(member);
		given(notificationSettingProvider.getOrCreate(memberId)).willReturn(setting);
		given(setting.isNotificationEnabled()).willReturn(true);
		given(setting.getStartTime()).willReturn(LocalTime.MIN);
		given(setting.getEndTime()).willReturn(LocalTime.MAX);
		given(setting.getDaysOfWeek()).willReturn("MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY,SATURDAY,SUNDAY");
		given(setting.getNotificationRadius()).willReturn(NotificationRadius.R300);
		given(request.getStoreId()).willReturn(1L);
		given(request.getLatitude()).willReturn(37.5);
		given(request.getLongitude()).willReturn(127.0);
		given(storeRepository.findById(1L)).willReturn(Optional.of(store));
		given(subscribedStoreRepository.existsSubscribedStoreWithinRadius(memberId, 1L, 37.5, 127.0, 300))
			.willReturn(true);
		given(storeNotificationRepository.tryAdvisoryXactLock(4294967297L)).willReturn(true);
		given(storeNotificationRepository.existsByMemberAndStoreAndSentAtAfter(eq(member), eq(store), any()))
			.willReturn(false);
		given(fcmTokenRepository.findByMemberAndIsActive(member, true)).willReturn(List.of(tokenA, tokenB));
		doThrow(new AlarmException(AlarmErrorCode.FCM_SEND_FAILED))
			.when(fcmService).sendPushNotification(eq("token-a"), any(), any(), any());

		alarmCommandService.handleGeofenceEnter(memberId, request);

		verify(storeNotificationRepository, times(1)).save(any());
	}

	// ───────────────────────── toggleMarketingEnabled ─────────────────────────

	@Test
	@DisplayName("toggleMarketingEnabled: 설정에서 켜면 마케팅 동의와 동의 시각도 남긴다")
	void toggleMarketingEnabled_on_recordsConsent() {
		Member member = Member.builder().nickname("테스터").build();
		NotificationSetting setting = NotificationSetting.builder().member(member).isMarketingEnabled(false).build();
		given(notificationSettingProvider.getOrCreate(1L)).willReturn(setting);
		given(memberReader.getMember(1L)).willReturn(member);

		MarketingEnabledResponse response = alarmCommandService.toggleMarketingEnabled(1L);

		assertThat(response.isMarketingEnabled()).isTrue();
		assertThat(member.isAgreeToMarketing()).isTrue();
		assertThat(member.getMarketingAgreedAt()).isNotNull();
	}

	@Test
	@DisplayName("toggleMarketingEnabled: 설정에서 끄면 마케팅·야간 동의가 철회되고 동의 시각을 지운다")
	void toggleMarketingEnabled_off_withdrawsConsent() {
		Member member = Member.builder().nickname("테스터").build();
		member.updateMarketingAgreement(true);
		member.updateNightMarketingAgreement(true);
		NotificationSetting setting = NotificationSetting.builder().member(member).isMarketingEnabled(true).build();
		given(notificationSettingProvider.getOrCreate(1L)).willReturn(setting);
		given(memberReader.getMember(1L)).willReturn(member);

		MarketingEnabledResponse response = alarmCommandService.toggleMarketingEnabled(1L);

		assertThat(response.isMarketingEnabled()).isFalse();
		assertThat(member.isAgreeToMarketing()).isFalse();
		assertThat(member.getMarketingAgreedAt()).isNull();
		assertThat(member.isAgreeToNightMarketing()).isFalse();
	}
}
