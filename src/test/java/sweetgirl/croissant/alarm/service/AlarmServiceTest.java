package sweetgirl.croissant.alarm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.alarm.code.AlarmErrorCode;
import sweetgirl.croissant.alarm.dto.request.GeofenceEnterRequest;
import sweetgirl.croissant.alarm.dto.response.GeofenceCandidatesResponse;
import sweetgirl.croissant.alarm.dto.response.StoreNotificationResponse;
import sweetgirl.croissant.alarm.entity.FcmToken;
import sweetgirl.croissant.alarm.entity.StoreNotification;
import sweetgirl.croissant.alarm.exception.AlarmException;
import sweetgirl.croissant.alarm.repository.FcmTokenRepository;
import sweetgirl.croissant.alarm.repository.StoreNotificationRepository;
import sweetgirl.croissant.common.enums.NotificationRadius;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.entity.NotificationSetting;
import sweetgirl.croissant.member.repository.MemberRepository;
import sweetgirl.croissant.member.repository.NotificationSettingRepository;
import sweetgirl.croissant.store.code.StoreErrorCode;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.exception.StoreException;
import sweetgirl.croissant.store.repository.GeofenceCandidateProjection;
import sweetgirl.croissant.store.repository.StoreRepository;
import sweetgirl.croissant.store.repository.SubscribedStoreRepository;

@ExtendWith(MockitoExtension.class)
class AlarmServiceTest {

	@Mock
	StoreNotificationRepository storeNotificationRepository;
	@Mock
	NotificationSettingRepository notificationSettingRepository;
	@Mock
	FcmTokenRepository fcmTokenRepository;
	@Mock
	FcmService fcmService;
	@Mock
	SubscribedStoreRepository subscribedStoreRepository;
	@Mock
	MemberRepository memberRepository;
	@Mock
	StoreRepository storeRepository;

	@InjectMocks
	AlarmService alarmService;

	// ───────────────────────── getStoreNotificationHistory ─────────────────────────

	@Test
	@DisplayName("getStoreNotificationHistory: 최근 7일 알림을 DTO 리스트로 반환한다")
	void getStoreNotificationHistory_returnsDtoList() {
		Long memberId = 1L;
		Member member = mock(Member.class);
		Store store = mock(Store.class);
		given(store.getName()).willReturn("뚜레쥬르");

		LocalDateTime sentAt = LocalDateTime.now().minusDays(3);
		StoreNotification notification = mock(StoreNotification.class);
		given(notification.getId()).willReturn(1L);
		given(notification.getStore()).willReturn(store);
		given(notification.getMessage()).willReturn("오픈했어요");
		given(notification.getSentAt()).willReturn(sentAt);
		given(notification.getReadAt()).willReturn(null);

		given(memberRepository.findById(memberId)).willReturn(Optional.of(member));
		given(storeNotificationRepository.findByMemberAndSentAtAfterOrderBySentAtDesc(
			eq(member), any(LocalDateTime.class)))
			.willReturn(List.of(notification));

		List<StoreNotificationResponse> result = alarmService.getStoreNotificationHistory(memberId);

		assertThat(result).hasSize(1);
		assertThat(result.get(0).getId()).isEqualTo(1L);
		assertThat(result.get(0).getStoreName()).isEqualTo("뚜레쥬르");
		assertThat(result.get(0).getMessage()).isEqualTo("오픈했어요");
		assertThat(result.get(0).getSentAt()).isEqualTo(sentAt);
		assertThat(result.get(0).isRead()).isFalse();
		assertThat(result.get(0).getReadAt()).isNull();
	}

	@Test
	@DisplayName("getStoreNotificationHistory: 알림 없으면 빈 리스트를 반환한다")
	void getStoreNotificationHistory_returnsEmptyList_whenNoNotifications() {
		Long memberId = 1L;
		Member member = mock(Member.class);
		given(memberRepository.findById(memberId)).willReturn(Optional.of(member));
		given(storeNotificationRepository.findByMemberAndSentAtAfterOrderBySentAtDesc(
			eq(member), any(LocalDateTime.class)))
			.willReturn(List.of());

		List<StoreNotificationResponse> result = alarmService.getStoreNotificationHistory(memberId);

		assertThat(result).isEmpty();
	}

	@Test
	@DisplayName("getStoreNotificationHistory: readAt이 있으면 isRead는 true다")
	void getStoreNotificationHistory_isReadTrue_whenReadAtExists() {
		Long memberId = 1L;
		Member member = mock(Member.class);
		Store store = mock(Store.class);
		given(store.getName()).willReturn("파리바게뜨");

		LocalDateTime readAt = LocalDateTime.now();
		StoreNotification notification = mock(StoreNotification.class);
		given(notification.getId()).willReturn(2L);
		given(notification.getStore()).willReturn(store);
		given(notification.getMessage()).willReturn("오픈");
		given(notification.getSentAt()).willReturn(LocalDateTime.now().minusDays(1));
		given(notification.getReadAt()).willReturn(readAt);

		given(memberRepository.findById(memberId)).willReturn(Optional.of(member));
		given(storeNotificationRepository.findByMemberAndSentAtAfterOrderBySentAtDesc(
			eq(member), any(LocalDateTime.class)))
			.willReturn(List.of(notification));

		List<StoreNotificationResponse> result = alarmService.getStoreNotificationHistory(memberId);

		assertThat(result.get(0).isRead()).isTrue();
		assertThat(result.get(0).getReadAt()).isEqualTo(readAt);
	}

	// ───────────────────────── handleGeofenceEnter ─────────────────────────

	@Test
	@DisplayName("handleGeofenceEnter: 알림 OFF이면 FCM 발송 없이 종료한다")
	void handleGeofenceEnter_notificationDisabled_silentReturn() {
		Long memberId = 1L;
		Member member = mock(Member.class);
		NotificationSetting setting = mock(NotificationSetting.class);
		GeofenceEnterRequest request = mock(GeofenceEnterRequest.class);

		given(memberRepository.findById(memberId)).willReturn(Optional.of(member));
		given(notificationSettingRepository.findByMemberId(memberId)).willReturn(Optional.of(setting));
		given(setting.isNotificationEnabled()).willReturn(false);

		alarmService.handleGeofenceEnter(memberId, request);

		verify(storeNotificationRepository, never()).save(any());
	}

	@Test
	@DisplayName("handleGeofenceEnter: storeId에 해당하는 가게가 없으면 StoreException을 던진다")
	void handleGeofenceEnter_storeNotFound_throwsException() {
		Long memberId = 1L;
		Member member = mock(Member.class);
		NotificationSetting setting = mock(NotificationSetting.class);
		GeofenceEnterRequest request = mock(GeofenceEnterRequest.class);

		given(memberRepository.findById(memberId)).willReturn(Optional.of(member));
		given(notificationSettingRepository.findByMemberId(memberId)).willReturn(Optional.of(setting));
		given(setting.isNotificationEnabled()).willReturn(true);
		given(setting.isTimeRestricted()).willReturn(false);
		given(setting.getDaysOfWeek()).willReturn("MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY,SATURDAY,SUNDAY");
		given(request.getStoreId()).willReturn(99L);
		given(storeRepository.findById(99L)).willReturn(Optional.empty());

		assertThatThrownBy(() -> alarmService.handleGeofenceEnter(memberId, request))
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

		given(memberRepository.findById(memberId)).willReturn(Optional.of(member));
		given(notificationSettingRepository.findByMemberId(memberId)).willReturn(Optional.of(setting));
		given(setting.isNotificationEnabled()).willReturn(true);
		given(setting.isTimeRestricted()).willReturn(false);
		given(setting.getDaysOfWeek()).willReturn("MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY,SATURDAY,SUNDAY");
		given(setting.getNotificationRadius()).willReturn(NotificationRadius.RADIUS_300);
		given(request.getStoreId()).willReturn(1L);
		given(request.getLatitude()).willReturn(37.5);
		given(request.getLongitude()).willReturn(127.0);
		given(storeRepository.findById(1L)).willReturn(Optional.of(store));
		given(subscribedStoreRepository.existsSubscribedStoreWithinRadius(memberId, 1L, 37.5, 127.0, 300))
			.willReturn(false);

		assertThatThrownBy(() -> alarmService.handleGeofenceEnter(memberId, request))
			.isInstanceOf(AlarmException.class)
			.hasMessageContaining(AlarmErrorCode.GEOFENCE_NOT_SUBSCRIBED.getMessage());
	}

	@Test
	@DisplayName("handleGeofenceEnter: 오늘 이미 알림을 보낸 가게면 FCM 발송 없이 종료한다")
	void handleGeofenceEnter_alreadyNotifiedToday_silentReturn() {
		Long memberId = 1L;
		Member member = mock(Member.class);
		Store store = mock(Store.class);
		NotificationSetting setting = mock(NotificationSetting.class);
		GeofenceEnterRequest request = mock(GeofenceEnterRequest.class);

		given(memberRepository.findById(memberId)).willReturn(Optional.of(member));
		given(notificationSettingRepository.findByMemberId(memberId)).willReturn(Optional.of(setting));
		given(setting.isNotificationEnabled()).willReturn(true);
		given(setting.isTimeRestricted()).willReturn(false);
		given(setting.getDaysOfWeek()).willReturn("MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY,SATURDAY,SUNDAY");
		given(setting.getNotificationRadius()).willReturn(NotificationRadius.RADIUS_300);
		given(request.getStoreId()).willReturn(1L);
		given(request.getLatitude()).willReturn(37.5);
		given(request.getLongitude()).willReturn(127.0);
		given(storeRepository.findById(1L)).willReturn(Optional.of(store));
		given(subscribedStoreRepository.existsSubscribedStoreWithinRadius(memberId, 1L, 37.5, 127.0, 300))
			.willReturn(true);
		given(storeNotificationRepository.existsByMemberAndStoreAndSentAtAfter(eq(member), eq(store), any()))
			.willReturn(true);

		alarmService.handleGeofenceEnter(memberId, request);

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

		given(memberRepository.findById(memberId)).willReturn(Optional.of(member));
		given(notificationSettingRepository.findByMemberId(memberId)).willReturn(Optional.of(setting));
		given(setting.isNotificationEnabled()).willReturn(true);
		given(setting.isTimeRestricted()).willReturn(false);
		given(setting.getDaysOfWeek()).willReturn("MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY,SATURDAY,SUNDAY");
		given(setting.getNotificationRadius()).willReturn(NotificationRadius.RADIUS_300);
		given(request.getStoreId()).willReturn(1L);
		given(request.getLatitude()).willReturn(37.5);
		given(request.getLongitude()).willReturn(127.0);
		given(storeRepository.findById(1L)).willReturn(Optional.of(store));
		given(subscribedStoreRepository.existsSubscribedStoreWithinRadius(memberId, 1L, 37.5, 127.0, 300))
			.willReturn(true);
		given(storeNotificationRepository.existsByMemberAndStoreAndSentAtAfter(eq(member), eq(store), any()))
			.willReturn(false);
		given(fcmTokenRepository.findByMemberAndIsActive(member, true)).willReturn(List.of(token));
		doThrow(new AlarmException(AlarmErrorCode.FCM_SEND_FAILED))
			.when(fcmService).sendPushNotification(any(), any(), any());

		alarmService.handleGeofenceEnter(memberId, request);

		verify(storeNotificationRepository, never()).save(any());
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

		given(memberRepository.findById(memberId)).willReturn(Optional.of(member));
		given(notificationSettingRepository.findByMemberId(memberId)).willReturn(Optional.of(setting));
		given(setting.isNotificationEnabled()).willReturn(true);
		given(setting.isTimeRestricted()).willReturn(false);
		given(setting.getDaysOfWeek()).willReturn("MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY,SATURDAY,SUNDAY");
		given(setting.getNotificationRadius()).willReturn(NotificationRadius.RADIUS_300);
		given(request.getStoreId()).willReturn(1L);
		given(request.getLatitude()).willReturn(37.5);
		given(request.getLongitude()).willReturn(127.0);
		given(storeRepository.findById(1L)).willReturn(Optional.of(store));
		given(subscribedStoreRepository.existsSubscribedStoreWithinRadius(memberId, 1L, 37.5, 127.0, 300))
			.willReturn(true);
		given(storeNotificationRepository.existsByMemberAndStoreAndSentAtAfter(eq(member), eq(store), any()))
			.willReturn(false);
		given(fcmTokenRepository.findByMemberAndIsActive(member, true)).willReturn(List.of(tokenA, tokenB));
		doThrow(new AlarmException(AlarmErrorCode.FCM_SEND_FAILED))
			.when(fcmService).sendPushNotification(eq("token-a"), any(), any());

		alarmService.handleGeofenceEnter(memberId, request);

		verify(storeNotificationRepository, times(1)).save(any());
	}

	// ───────────────────────── getGeofenceCandidates ─────────────────────────

	@Test
	@DisplayName("getGeofenceCandidates: 알림 OFF이면 빈 리스트를 반환한다")
	void getGeofenceCandidates_notificationDisabled_returnsEmpty() {
		Long memberId = 1L;
		Member member = mock(Member.class);
		NotificationSetting setting = mock(NotificationSetting.class);

		given(memberRepository.findById(memberId)).willReturn(Optional.of(member));
		given(notificationSettingRepository.findByMemberId(memberId)).willReturn(Optional.of(setting));
		given(setting.isNotificationEnabled()).willReturn(false);

		GeofenceCandidatesResponse result = alarmService.getGeofenceCandidates(memberId, 37.5, 127.0);

		assertThat(result.getStores()).isEmpty();
	}

	@Test
	@DisplayName("getGeofenceCandidates: 정상 조건이면 후보 가게 목록을 반환한다")
	void getGeofenceCandidates_normal_returnsCandidates() {
		Long memberId = 1L;
		Member member = mock(Member.class);
		NotificationSetting setting = mock(NotificationSetting.class);
		GeofenceCandidateProjection projection = mock(GeofenceCandidateProjection.class);

		given(memberRepository.findById(memberId)).willReturn(Optional.of(member));
		given(notificationSettingRepository.findByMemberId(memberId)).willReturn(Optional.of(setting));
		given(setting.isNotificationEnabled()).willReturn(true);
		given(setting.getNotificationRadius()).willReturn(NotificationRadius.RADIUS_300);
		given(projection.getStoreId()).willReturn(1L);
		given(projection.getStoreName()).willReturn("뚜레쥬르");
		given(projection.getLatitude()).willReturn(37.5);
		given(projection.getLongitude()).willReturn(127.0);
		given(projection.getDistanceMeters()).willReturn(150.0);
		given(subscribedStoreRepository.findGeofenceCandidates(eq(memberId), eq(37.5), eq(127.0), anyDouble()))
			.willReturn(List.of(projection));

		GeofenceCandidatesResponse result = alarmService.getGeofenceCandidates(memberId, 37.5, 127.0);

		assertThat(result.getStores()).hasSize(1);
		assertThat(result.getStores().get(0).getName()).isEqualTo("뚜레쥬르");
		assertThat(result.getStores().get(0).getRadiusMeters()).isEqualTo(300);
	}

}
