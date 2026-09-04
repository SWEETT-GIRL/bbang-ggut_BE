package sweetgirl.croissant.alarm.dto.response;

import java.time.LocalDateTime;

import sweetgirl.croissant.alarm.entity.Notification;
import sweetgirl.croissant.alarm.enums.NotificationTargetType;
import sweetgirl.croissant.alarm.enums.NotificationType;

/**
 * 알림 한 줄.
 *
 * <p>문구를 누가 갖느냐가 타입마다 다르다. 저장·좋아요 같은 활동 알림은 재료(actorNickname,
 * targetTitle)만 내리고 앱이 조립한다 — 서버가 문장을 박아 내리면 앱에서 못 고친다.
 * 공지는 반대로 서버가 title·body를 그대로 준다. 그래야 앱 배포 없이 문구를 고칠 수 있다.
 */
public record NotificationResponse(
		Long notificationId,
		NotificationType type,
		boolean read,
		LocalDateTime notifiedAt,

		/** 탈퇴했으면 null. 공지처럼 사람이 한 일이 아니면 애초에 없다 */
		Long actorId,
		String actorNickname,

		NotificationTargetType targetType,
		Long targetId,
		String targetTitle,

		/** 공지용. 활동 알림에는 없다 */
		String title,
		String body,
		String link) {

	/** 탈퇴한 사람의 행동도 일어난 일이다 — 줄을 없애는 대신 이름만 가린다. */
	private static final String WITHDRAWN_NICKNAME = "알 수 없음";

	public static NotificationResponse from(Notification notification) {
		return new NotificationResponse(
			notification.getId(),
			notification.getType(),
			notification.isRead(),
			notification.getNotifiedAt(),
			actorId(notification),
			actorNickname(notification),
			notification.getTargetType(),
			notification.getTargetId(),
			notification.getTargetTitle(),
			notification.getTitle(),
			notification.getBody(),
			notification.getLink());
	}

	private static Long actorId(Notification notification) {
		if (notification.getActor() == null || notification.getActor().isDeleted()) {
			return null;
		}
		return notification.getActor().getId();
	}

	private static String actorNickname(Notification notification) {
		if (notification.getActor() == null) {
			return null;
		}
		return notification.getActor().isDeleted()
			? WITHDRAWN_NICKNAME
			: notification.getActor().getNickname();
	}
}
