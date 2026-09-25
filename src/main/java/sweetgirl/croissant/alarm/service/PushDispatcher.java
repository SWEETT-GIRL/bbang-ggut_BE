package sweetgirl.croissant.alarm.service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.alarm.entity.FcmToken;
import sweetgirl.croissant.alarm.entity.Notification;
import sweetgirl.croissant.alarm.entity.NotificationDelivery;
import sweetgirl.croissant.alarm.enums.DeliveryStatus;
import sweetgirl.croissant.alarm.enums.NotificationChannel;
import sweetgirl.croissant.alarm.exception.AlarmException;
import sweetgirl.croissant.alarm.repository.FcmTokenRepository;
import sweetgirl.croissant.alarm.repository.NotificationDeliveryRepository;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.entity.NotificationSetting;
import sweetgirl.croissant.member.repository.NotificationSettingRepository;

/**
 * 알림을 푸시로 내보낸다. 알림함에 앉히는 일은 {@link NotificationRecorder}가 이미 끝냈고,
 * 여기서 하는 일은 "이 사람에게 지금 밖으로 내보내도 되는가"를 판정하고 결과를 대장에 적는 것뿐이다.
 *
 * <p>보내지 않기로 한 경우는 대장에 줄을 만들지 않는다. 우편함에 행이 있는데 대장에 PUSH 줄이
 * 없으면 그것이 곧 "푸시는 보내지 않았다"는 뜻이다 — 안 보낸 이유까지 남기려면 사유 코드를
 * 관리해야 하는데, 그것을 볼 화면이 아직 없다.
 *
 * <p>방해금지 시간(startTime·endTime·daysOfWeek)은 보지 않는다. 지키려면 그 시간까지 미뤘다
 * 보내는 예약 발송이 필요한데 스케줄러가 없다. 지금 그것을 보면 알림이 밀리는 게 아니라
 * 그냥 사라진다.
 */
@Component
@RequiredArgsConstructor
public class PushDispatcher {

	private static final Logger log = LoggerFactory.getLogger(PushDispatcher.class);
	private static final int NIGHT_START_HOUR = 21;
	private static final int NIGHT_END_HOUR = 8;

	private final NotificationSettingRepository notificationSettingRepository;
	private final FcmTokenRepository fcmTokenRepository;
	private final NotificationDeliveryRepository deliveryRepository;
	private final FcmService fcmService;

	/**
	 * 한 사람에게 보낸다. 부르는 쪽의 트랜잭션과 분리한다 — FCM 응답을 기다리는 동안 알림을
	 * 적재한 트랜잭션이 DB 커넥션을 잡고 있으면 안 되고, 발송 실패가 적재를 되돌려서도 안 된다.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void dispatch(Notification notification, Member member, LocalDateTime now) {
		if (!isEligible(notification, member, now)) {
			return;
		}

		List<String> tokens = activeTokensOf(member);
		if (tokens.isEmpty()) {
			log.info("푸시 skip — 활성 토큰 없음 [memberId={}, type={}]",
				member.getId(), notification.getType());
			return;
		}

		Map<String, String> data = pushDataOf(notification);
		String title = notification.getTitle();
		String body = notification.getBody();

		// 기기 하나만 성공해도 그 사람에게는 도착한 것이다. 전부 실패했을 때만 FAILED 로 남긴다.
		boolean anySent = false;
		String lastFailure = null;
		for (String token : tokens) {
			try {
				fcmService.sendPushNotification(token, title, body, data);
				anySent = true;
			} catch (AlarmException e) {
				lastFailure = e.getMessage();
			}
		}

		record(notification, member, anySent, lastFailure, now);
	}

	private void record(Notification notification, Member member, boolean sent, String failReason,
			LocalDateTime now) {
		deliveryRepository
			.findByNotificationIdAndMemberIdAndChannel(
				notification.getId(), member.getId(), NotificationChannel.PUSH)
			.ifPresentOrElse(
				existing -> existing.retried(
					sent ? DeliveryStatus.SENT : DeliveryStatus.FAILED, sent ? null : failReason, now),
				() -> deliveryRepository.save(sent
					? NotificationDelivery.sent(notification, member, NotificationChannel.PUSH, now)
					: NotificationDelivery.failed(
						notification, member, NotificationChannel.PUSH, failReason, now)));
	}

	/**
	 * 보낼 자격. 알림 수신을 끈 사람에게는 무엇도 보내지 않고, 광고는 마케팅 동의를 따로 본다 —
	 * 광고성 정보는 일반 알림 동의로 갈음할 수 없다. 야간(21~08시)의 광고는 야간 동의까지 본다.
	 *
	 * <p>설정이 없으면 기본값(수신 켬)으로 본다. 여기서 설정을 만들어 두면 공지 한 번에 회원 수만큼
	 * INSERT가 나간다 — 설정은 사용자가 그 화면을 열 때 만들어진다.
	 */
	private boolean isEligible(Notification notification, Member member, LocalDateTime now) {
		NotificationSetting setting = notificationSettingRepository.findByMemberId(member.getId())
			.orElse(null);
		if (setting == null) {
			return !notification.getType().requiresMarketingConsent();
		}
		if (!setting.isNotificationEnabled()) {
			return false;
		}
		if (!notification.getType().requiresMarketingConsent()) {
			return true;
		}
		return setting.isMarketingEnabled() && (!isNightTime(now) || member.isAgreeToNightMarketing());
	}

	/**
	 * 21:00~08:00(한국 시각)은 광고에 야간 동의가 따로 필요한 시간이다(정보통신망법 제50조).
	 * {@code now}는 서버 벽시계이고 서버는 Asia/Seoul로 돈다(docker-compose의 TZ·user.timezone).
	 * 이 시간에 야간 동의가 없는 사람은 그냥 건너뛴다 — 아침까지 미뤄 보낼 스케줄러가 없다.
	 */
	private boolean isNightTime(LocalDateTime now) {
		int hour = now.getHour();
		return hour >= NIGHT_START_HOUR || hour < NIGHT_END_HOUR;
	}

	/** 같은 기기가 여러 줄로 남은 옛 데이터가 있어 토큰 문자열로 한 번 접는다. */
	private List<String> activeTokensOf(Member member) {
		return fcmTokenRepository.findByMemberAndIsActive(member, true).stream()
			.map(FcmToken::getToken)
			.distinct()
			.toList();
	}

	/**
	 * 앱이 알림을 눌렀을 때 읽는 값들. payload를 그대로 싣고 그 위에 타입과 대상만 얹는다 —
	 * 인앱 목록이 쓰는 재료와 푸시가 쓰는 재료가 같아야 두 경로가 어긋나지 않는다.
	 */
	private Map<String, String> pushDataOf(Notification notification) {
		Map<String, String> data = new HashMap<>();
		if (notification.getPayload() != null) {
			data.putAll(notification.getPayload());
		}
		data.put("type", notification.getType().pushType());
		data.put("notificationId", String.valueOf(notification.getId()));
		if (notification.getTargetType() != null && notification.getTargetId() != null) {
			data.put(targetKeyOf(notification), String.valueOf(notification.getTargetId()));
		}
		return data;
	}

	/**
	 * 대상 id를 싣는 키. 앱은 {@code storeId}·{@code courseId}처럼 도메인 이름으로 읽는다 —
	 * 통합 저장에서 target으로 접었다고 해서 나가는 이름까지 바꾸면 스토어에 깔린 앱이 못 알아본다.
	 */
	private String targetKeyOf(Notification notification) {
		return switch (notification.getTargetType()) {
			case STORE -> "storeId";
			case COURSE -> "courseId";
			case RECORD -> "recordId";
			case LETTER -> "letterId";
		};
	}
}
