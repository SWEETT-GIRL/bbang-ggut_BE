package sweetgirl.croissant.alarm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.alarm.dto.response.GeofenceCandidatesResponse;
import sweetgirl.croissant.alarm.dto.response.StoreNotificationResponse;
import sweetgirl.croissant.alarm.entity.StoreNotification;
import sweetgirl.croissant.alarm.repository.StoreNotificationRepository;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.entity.NotificationSetting;
import sweetgirl.croissant.member.enums.NotificationRadius;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.store.dto.projection.GeofenceCandidateProjection;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.repository.SubscribedStoreRepository;

@ExtendWith(MockitoExtension.class)
class AlarmQueryServiceTest {

	@Mock
	StoreNotificationRepository storeNotificationRepository;
	@Mock
	SubscribedStoreRepository subscribedStoreRepository;
	@Mock
	MemberReader memberReader;
	@Mock
	NotificationSettingProvider notificationSettingProvider;

	@InjectMocks
	AlarmQueryService alarmQueryService;

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

		given(memberReader.getMember(memberId)).willReturn(member);
		given(storeNotificationRepository.findByMemberAndSentAtAfterOrderBySentAtDesc(
			eq(member), any(LocalDateTime.class)))
			.willReturn(List.of(notification));

		List<StoreNotificationResponse> result = alarmQueryService.getStoreNotificationHistory(memberId);

		assertThat(result).hasSize(1);
		assertThat(result.get(0).getId()).isEqualTo(1L);
		assertThat(result.get(0).getStoreName()).isEqualTo("뚜레쥬르");
		assertThat(result.get(0).getMessage()).isEqualTo("오픈했어요");
		assertThat(result.get(0).getSentAt()).isEqualTo(sentAt);
		assertThat(result.get(0).isRead()).isFalse();
		assertThat(result.get(0).getReadAt()).isNull();
	}

	@Test
	@DisplayName("getStoreNotificationHistory: 응답 DTO에 storeId가 포함된다")
	void getStoreNotificationHistory_includesStoreId() {
		Long memberId = 1L;
		Member member = mock(Member.class);
		Store store = mock(Store.class);
		given(store.getId()).willReturn(10L);
		given(store.getName()).willReturn("뚜레쥬르");

		StoreNotification notification = mock(StoreNotification.class);
		given(notification.getId()).willReturn(1L);
		given(notification.getStore()).willReturn(store);
		given(notification.getMessage()).willReturn("오픈했어요");
		given(notification.getSentAt()).willReturn(LocalDateTime.now().minusDays(1));
		given(notification.getReadAt()).willReturn(null);

		given(memberReader.getMember(memberId)).willReturn(member);
		given(storeNotificationRepository.findByMemberAndSentAtAfterOrderBySentAtDesc(
			eq(member), any(LocalDateTime.class)))
			.willReturn(List.of(notification));

		List<StoreNotificationResponse> result = alarmQueryService.getStoreNotificationHistory(memberId);

		assertThat(result.get(0).getStoreId()).isEqualTo(10L);
	}

	@Test
	@DisplayName("getStoreNotificationHistory: 알림 없으면 빈 리스트를 반환한다")
	void getStoreNotificationHistory_returnsEmptyList_whenNoNotifications() {
		Long memberId = 1L;
		Member member = mock(Member.class);
		given(memberReader.getMember(memberId)).willReturn(member);
		given(storeNotificationRepository.findByMemberAndSentAtAfterOrderBySentAtDesc(
			eq(member), any(LocalDateTime.class)))
			.willReturn(List.of());

		List<StoreNotificationResponse> result = alarmQueryService.getStoreNotificationHistory(memberId);

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

		given(memberReader.getMember(memberId)).willReturn(member);
		given(storeNotificationRepository.findByMemberAndSentAtAfterOrderBySentAtDesc(
			eq(member), any(LocalDateTime.class)))
			.willReturn(List.of(notification));

		List<StoreNotificationResponse> result = alarmQueryService.getStoreNotificationHistory(memberId);

		assertThat(result.get(0).isRead()).isTrue();
		assertThat(result.get(0).getReadAt()).isEqualTo(readAt);
	}

	// ───────────────────────── getGeofenceCandidates ─────────────────────────

	@Test
	@DisplayName("getGeofenceCandidates: 알림 OFF이면 빈 리스트를 반환한다")
	void getGeofenceCandidates_notificationDisabled_returnsEmpty() {
		Long memberId = 1L;
		NotificationSetting setting = mock(NotificationSetting.class);

		given(notificationSettingProvider.getOrCreate(memberId)).willReturn(setting);
		given(setting.isNotificationEnabled()).willReturn(false);

		GeofenceCandidatesResponse result = alarmQueryService.getGeofenceCandidates(memberId, 37.5, 127.0);

		assertThat(result.getStores()).isEmpty();
	}

	@Test
	@DisplayName("getGeofenceCandidates: 정상 조건이면 후보 가게 목록을 반환한다")
	void getGeofenceCandidates_normal_returnsCandidates() {
		Long memberId = 1L;
		NotificationSetting setting = mock(NotificationSetting.class);
		GeofenceCandidateProjection projection = mock(GeofenceCandidateProjection.class);

		given(notificationSettingProvider.getOrCreate(memberId)).willReturn(setting);
		given(setting.isNotificationEnabled()).willReturn(true);
		given(setting.getNotificationRadius()).willReturn(NotificationRadius.R300);
		given(projection.getStoreId()).willReturn(1L);
		given(projection.getStoreName()).willReturn("뚜레쥬르");
		given(projection.getLatitude()).willReturn(37.5);
		given(projection.getLongitude()).willReturn(127.0);
		given(projection.getDistanceMeters()).willReturn(150.0);
		given(subscribedStoreRepository.findGeofenceCandidates(eq(memberId), eq(37.5), eq(127.0), anyDouble()))
			.willReturn(List.of(projection));

		GeofenceCandidatesResponse result = alarmQueryService.getGeofenceCandidates(memberId, 37.5, 127.0);

		assertThat(result.getStores()).hasSize(1);
		assertThat(result.getStores().get(0).getName()).isEqualTo("뚜레쥬르");
		assertThat(result.getStores().get(0).getRadiusMeters()).isEqualTo(300);
	}
}
