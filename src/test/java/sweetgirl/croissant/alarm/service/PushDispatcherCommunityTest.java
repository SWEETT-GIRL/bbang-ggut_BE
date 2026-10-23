package sweetgirl.croissant.alarm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.alarm.entity.Notification;
import sweetgirl.croissant.alarm.enums.NotificationTargetType;
import sweetgirl.croissant.alarm.enums.NotificationType;
import sweetgirl.croissant.alarm.repository.FcmTokenRepository;
import sweetgirl.croissant.alarm.repository.NotificationDeliveryRepository;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.entity.NotificationSetting;
import sweetgirl.croissant.member.repository.NotificationSettingRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("PushDispatcher — 커뮤니티 알림 스위치 · 하루 상한")
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
			new NotificationDeliveryCommandService(deliveryRepository), fcmService, deliveryRepository);
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
	@DisplayName("톡 · 좋아요 · 팔로잉 소식은 오늘 5건을 넘기면 SKIPPED, 댓글은 상한 밖")
	void dailyCap() {
		given(notificationSettingRepository.findByMemberId(2L)).willReturn(Optional.of(setting()));
		given(deliveryRepository.countSentPushSince(anyLong(), any(), any()))
			.willReturn((long)PushDispatcher.DAILY_PUSH_CAP);

		assertThat(pushDispatcher.dispatchWithResult(notification(NotificationType.RECORD_LIKE), member,
			LocalDateTime.now())).isEqualTo(PushDispatcher.Result.SKIPPED);
		then(fcmTokenRepository).shouldHaveNoInteractions();

		// 댓글은 상한을 안 본다 — 토큰 조회까지 간다(토큰이 없어 SKIPPED 로 끝나지만 경로가 다르다)
		pushDispatcher.dispatchWithResult(notification(NotificationType.RECORD_COMMENT), member, LocalDateTime.now());
		then(fcmTokenRepository).should().findByMemberAndIsActive(member, true);
	}

	@Test
	@DisplayName("설정 행이 없는 사람(설정 화면을 연 적 없음)에게도 하루 상한은 건다")
	void capAppliesWithoutSetting() {
		given(notificationSettingRepository.findByMemberId(2L)).willReturn(Optional.empty());
		given(deliveryRepository.countSentPushSince(anyLong(), any(), any())).willReturn(5L);

		PushDispatcher.Result result = pushDispatcher.dispatchWithResult(notification(NotificationType.RECORD_LIKE),
			member, LocalDateTime.now());

		assertThat(result).isEqualTo(PushDispatcher.Result.SKIPPED);
		then(fcmTokenRepository).shouldHaveNoInteractions();
	}
}
