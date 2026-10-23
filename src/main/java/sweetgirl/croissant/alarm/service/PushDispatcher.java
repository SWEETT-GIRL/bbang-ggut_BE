package sweetgirl.croissant.alarm.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.alarm.entity.FcmToken;
import sweetgirl.croissant.alarm.entity.Notification;
import sweetgirl.croissant.alarm.enums.NotificationType;
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
	private final NotificationDeliveryCommandService deliveryCommandService;
	private final FcmService fcmService;
	private final NotificationDeliveryRepository deliveryRepository;

	/** 톡 · 좋아요 · 팔로잉 소식은 하루(KST)에 이만큼만 울린다. 알림함에는 다 남는다. */
	static final int DAILY_PUSH_CAP = 5;
	private static final List<NotificationType> DAILY_CAPPED_TYPES = List.of(
		NotificationType.PAT, NotificationType.RECORD_LIKE, NotificationType.FOLLOWING_NEW_RECORD);
	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	/**
	 * 외부 호출에는 트랜잭션을 열지 않는다. 호출자는 DB 작업을 먼저 커밋하고 호출해야 한다.
	 */
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public void dispatch(Notification notification, Member member, LocalDateTime now) {
		dispatchWithResult(notification, member, now);
	}

	public enum Result {
		SENT, FAILED, SKIPPED
	}

	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public Result dispatchWithResult(Notification notification, Member member, LocalDateTime now) {
		if (!isEligible(notification, member, now)) {
			return Result.SKIPPED;
		}

		List<String> tokens = activeTokensOf(member);
		if (tokens.isEmpty()) {
			log.info("푸시 skip — 활성 토큰 없음 [memberId={}, type={}]",
				member.getId(), notification.getType());
			return Result.SKIPPED;
		}

		Map<String, String> data = pushDataOf(notification);
		String title = notification.getTitle();
		String body = notification.getBody();

		// FCM이 한 기기의 요청이라도 접수하면 성공이다. 실제 기기 표시 여부는 별도다.
		boolean anySent = false;
		String lastFailure = null;
		long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(2);
		for (String token : tokens) {
			if (System.nanoTime() >= deadline) {
				lastFailure = "PUSH_TIME_BUDGET_EXCEEDED";
				break;
			}
			try {
				FcmService.SendResult result = fcmService.sendPushNotification(token, title, body, data);
				anySent |= result == FcmService.SendResult.SENT;
			} catch (AlarmException e) {
				lastFailure = "FCM_SEND_FAILED";
			}
		}

		if (!anySent && lastFailure == null) {
			return Result.SKIPPED;
		}
		deliveryCommandService.recordDelivery(notification, member, anySent, lastFailure, now);
		return anySent ? Result.SENT : Result.FAILED;
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
		NotificationType type = notification.getType();
		// 하루 상한은 설정이 없는 사람(설정 화면을 연 적 없음)에게도 건다 — 톡 · 좋아요 · 팔로잉 소식 도배 방지
		if (type.isDailyCapped() && deliveryRepository.countSentPushSince(member.getId(),
			LocalDate.now(KST).atStartOfDay(), DAILY_CAPPED_TYPES) >= DAILY_PUSH_CAP) {
			return false;
		}
		if (setting == null) {
			return !type.requiresMarketingConsent();
		}
		if (!setting.isNotificationEnabled()) {
			return false;
		}
		if (type.isCommunityActivity() && !setting.allowsCommunity(type)) {
			return false;
		}
		if (!type.requiresMarketingConsent()) {
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
			// "admin."으로 시작하는 값은 어드민만 본다(편지 발송 이름 같은) — 사용자 기기로 내보내지 않는다
			notification.getPayload().forEach((key, value) -> {
				if (!key.startsWith(Notification.ADMIN_PAYLOAD_PREFIX)) {
					data.put(key, value);
				}
			});
		}
		// 알림함의 link와 푸시의 목적지가 같아야 한다. announcement는 payload 없이 deepLink만 저장할 수 있다.
		if (notification.getDeepLink() != null && !notification.getDeepLink().isBlank()) {
			data.put("link", notification.getDeepLink());
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
			case MEMBER -> "memberId";
			case COMMENT -> "commentId";
		};
	}
}
