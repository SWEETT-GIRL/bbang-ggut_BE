package sweetgirl.croissant.alarm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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

import sweetgirl.croissant.alarm.dto.response.StoreNotificationResponse;
import sweetgirl.croissant.alarm.entity.StoreNotification;
import sweetgirl.croissant.alarm.repository.StoreNotificationRepository;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.store.entity.Store;

@ExtendWith(MockitoExtension.class)
class AlarmServiceTest {

	@Mock
	StoreNotificationRepository storeNotificationRepository;

	@InjectMocks
	AlarmService alarmService;

	@Test
	@DisplayName("getStoreNotificationHistory: 최근 7일 알림을 DTO 리스트로 반환한다")
	void getStoreNotificationHistory_returnsDtoList() {
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

		given(storeNotificationRepository.findByMemberAndSentAtAfterOrderBySentAtDesc(
			eq(member), any(LocalDateTime.class)))
			.willReturn(List.of(notification));

		List<StoreNotificationResponse> result = alarmService.getStoreNotificationHistory(member);

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
		Member member = mock(Member.class);
		given(storeNotificationRepository.findByMemberAndSentAtAfterOrderBySentAtDesc(
			eq(member), any(LocalDateTime.class)))
			.willReturn(List.of());

		List<StoreNotificationResponse> result = alarmService.getStoreNotificationHistory(member);

		assertThat(result).isEmpty();
	}

	@Test
	@DisplayName("getStoreNotificationHistory: readAt이 있으면 isRead는 true다")
	void getStoreNotificationHistory_isReadTrue_whenReadAtExists() {
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

		given(storeNotificationRepository.findByMemberAndSentAtAfterOrderBySentAtDesc(
			eq(member), any(LocalDateTime.class)))
			.willReturn(List.of(notification));

		List<StoreNotificationResponse> result = alarmService.getStoreNotificationHistory(member);

		assertThat(result.get(0).isRead()).isTrue();
		assertThat(result.get(0).getReadAt()).isEqualTo(readAt);
	}
}
