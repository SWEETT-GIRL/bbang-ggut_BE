package sweetgirl.croissant.alarm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.alarm.dto.request.LocationCheckRequest;
import sweetgirl.croissant.alarm.dto.response.LocationCheckResponse;
import sweetgirl.croissant.alarm.dto.response.StoreNotificationResponse;
import sweetgirl.croissant.alarm.entity.FcmToken;
import sweetgirl.croissant.alarm.entity.StoreNotification;
import sweetgirl.croissant.alarm.repository.FcmTokenRepository;
import sweetgirl.croissant.alarm.repository.StoreNotificationRepository;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.entity.NotificationSetting;
import sweetgirl.croissant.member.repository.MemberRepository;
import sweetgirl.croissant.member.repository.NotificationSettingRepository;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.entity.SubscribedStore;
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

	// ───────────────────────── checkProximity ─────────────────────────

	@Test
	@DisplayName("checkProximity: 알림이 꺼져 있으면 triggered=false를 반환한다")
	void checkProximity_returnsNotTriggered_whenNotificationDisabled() {
		Long memberId = 1L;
		Member member = mock(Member.class);
		NotificationSetting setting = mock(NotificationSetting.class);
		LocationCheckRequest request = mock(LocationCheckRequest.class);

		given(memberRepository.findById(memberId)).willReturn(Optional.of(member));
		given(notificationSettingRepository.findByMember(member)).willReturn(Optional.of(setting));
		given(setting.isNotificationEnabled()).willReturn(false);

		LocationCheckResponse result = alarmService.checkProximity(memberId, request);

		assertThat(result.isTriggered()).isFalse();
		assertThat(result.getStoreNames()).isEmpty();
		verify(subscribedStoreRepository, never()).findNearbySubscribedStores(anyLong(), anyDouble(), anyDouble(),
			anyDouble());
	}

	@Test
	@DisplayName("checkProximity: DND ON이고 허용 요일이 아니면 triggered=false를 반환한다")
	void checkProximity_returnsNotTriggered_whenDndBlockedByDay() {
		Long memberId = 1L;
		Member member = mock(Member.class);
		NotificationSetting setting = mock(NotificationSetting.class);
		LocationCheckRequest request = mock(LocationCheckRequest.class);

		given(memberRepository.findById(memberId)).willReturn(Optional.of(member));
		given(notificationSettingRepository.findByMember(member)).willReturn(Optional.of(setting));
		given(setting.isNotificationEnabled()).willReturn(true);
		given(setting.isDndEnabled()).willReturn(true);
		given(setting.getStartTime()).willReturn(LocalTime.of(0, 0));
		given(setting.getEndTime()).willReturn(LocalTime.of(23, 59));
		given(setting.getDaysOfWeek()).willReturn(""); // 어떤 요일도 포함하지 않음

		LocationCheckResponse result = alarmService.checkProximity(memberId, request);

		assertThat(result.isTriggered()).isFalse();
		verify(subscribedStoreRepository, never()).findNearbySubscribedStores(anyLong(), anyDouble(), anyDouble(),
			anyDouble());
	}

	@Test
	@DisplayName("checkProximity: 반경 내 구독 가게가 없으면 triggered=false를 반환한다")
	void checkProximity_returnsNotTriggered_whenNoNearbyStores() {
		Long memberId = 1L;
		Member member = mock(Member.class);
		NotificationSetting setting = mock(NotificationSetting.class);
		LocationCheckRequest request = mock(LocationCheckRequest.class);

		given(memberRepository.findById(memberId)).willReturn(Optional.of(member));
		given(notificationSettingRepository.findByMember(member)).willReturn(Optional.of(setting));
		given(setting.isNotificationEnabled()).willReturn(true);
		given(setting.isDndEnabled()).willReturn(false);
		given(member.getId()).willReturn(memberId);
		given(request.getLatitude()).willReturn(37.5);
		given(request.getLongitude()).willReturn(127.0);
		given(setting.getNotificationRadius()).willReturn(300f);
		given(subscribedStoreRepository.findNearbySubscribedStores(memberId, 37.5, 127.0, 300f))
			.willReturn(List.of());
		given(fcmTokenRepository.findByMemberAndIsActive(member, true)).willReturn(List.of());

		LocationCheckResponse result = alarmService.checkProximity(memberId, request);

		assertThat(result.isTriggered()).isFalse();
		assertThat(result.getStoreNames()).isEmpty();
	}

	@Test
	@DisplayName("checkProximity: 오늘 이미 발송한 가게는 skip하고 triggered=false를 반환한다")
	void checkProximity_skipsAlreadyNotifiedStore() {
		Long memberId = 1L;
		Member member = mock(Member.class);
		NotificationSetting setting = mock(NotificationSetting.class);
		LocationCheckRequest request = mock(LocationCheckRequest.class);
		Store store = mock(Store.class);
		SubscribedStore subscribedStore = mock(SubscribedStore.class);

		given(memberRepository.findById(memberId)).willReturn(Optional.of(member));
		given(notificationSettingRepository.findByMember(member)).willReturn(Optional.of(setting));
		given(setting.isNotificationEnabled()).willReturn(true);
		given(setting.isDndEnabled()).willReturn(false);
		given(member.getId()).willReturn(memberId);
		given(request.getLatitude()).willReturn(37.5);
		given(request.getLongitude()).willReturn(127.0);
		given(setting.getNotificationRadius()).willReturn(300f);
		given(subscribedStore.getStore()).willReturn(store);
		given(subscribedStoreRepository.findNearbySubscribedStores(memberId, 37.5, 127.0, 300f))
			.willReturn(List.of(subscribedStore));
		given(fcmTokenRepository.findByMemberAndIsActive(member, true)).willReturn(List.of());
		// 오늘 이미 발송 기록 있음
		given(storeNotificationRepository.existsByMemberAndStoreAndSentAtAfter(eq(member), eq(store), any()))
			.willReturn(true);

		LocationCheckResponse result = alarmService.checkProximity(memberId, request);

		assertThat(result.isTriggered()).isFalse();
		verify(fcmService, never()).sendPushNotification(any(), any(), any());
		verify(storeNotificationRepository, never()).save(any(StoreNotification.class));
	}

	@Test
	@DisplayName("checkProximity: 조건 충족 시 FCM 발송, StoreNotification 저장, triggered=true 반환")
	void checkProximity_sendsFcmAndSavesNotification_whenEligible() {
		Long memberId = 1L;
		Member member = mock(Member.class);
		NotificationSetting setting = mock(NotificationSetting.class);
		LocationCheckRequest request = mock(LocationCheckRequest.class);
		Store store = mock(Store.class);
		SubscribedStore subscribedStore = mock(SubscribedStore.class);
		FcmToken token = mock(FcmToken.class);

		given(memberRepository.findById(memberId)).willReturn(Optional.of(member));
		given(notificationSettingRepository.findByMember(member)).willReturn(Optional.of(setting));
		given(setting.isNotificationEnabled()).willReturn(true);
		given(setting.isDndEnabled()).willReturn(false);
		given(member.getId()).willReturn(memberId);
		given(request.getLatitude()).willReturn(37.5);
		given(request.getLongitude()).willReturn(127.0);
		given(setting.getNotificationRadius()).willReturn(300f);
		given(store.getName()).willReturn("뚜레쥬르");
		given(subscribedStore.getStore()).willReturn(store);
		given(token.getToken()).willReturn("fcm-token-123");
		given(subscribedStoreRepository.findNearbySubscribedStores(memberId, 37.5, 127.0, 300f))
			.willReturn(List.of(subscribedStore));
		given(fcmTokenRepository.findByMemberAndIsActive(member, true)).willReturn(List.of(token));
		given(storeNotificationRepository.existsByMemberAndStoreAndSentAtAfter(eq(member), eq(store), any()))
			.willReturn(false);

		LocationCheckResponse result = alarmService.checkProximity(memberId, request);

		assertThat(result.isTriggered()).isTrue();
		assertThat(result.getStoreNames()).containsExactly("뚜레쥬르");
		verify(fcmService).sendPushNotification("fcm-token-123", "뚜레쥬르", "뚜레쥬르 근처에 있어요!");
		verify(storeNotificationRepository).save(any(StoreNotification.class));
	}
}
