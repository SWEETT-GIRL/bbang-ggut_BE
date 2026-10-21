package sweetgirl.croissant.alarm.dto.response;

import java.time.LocalDateTime;

/**
 * 구버전 앱이 기대하는 알림 한 줄. 저장 알림 전용 모양이다.
 *
 * <p>스토어에 깔린 옛 앱이 actorNickname·courseTitle을 꺼내 "{닉네임} 님이 '{제목}'를 찜했어요"를
 * 조립한다. 공지에는 그 재료가 없어 빈칸투성이 문장이 되므로, 옛 경로에는 저장 알림만 내려준다.
 *
 * <p>그 앱들이 다 올라오면 이 DTO와 /alarm/course-notifications를 함께 지운다.
 * 새 앱은 {@link NotificationResponse}를 쓴다.
 */
public record CourseSaveNotificationResponse(
		Long notificationId,
		String type,
		boolean read,
		LocalDateTime notifiedAt,
		Long actorId,
		String actorNickname,
		Long courseId,
		String courseTitle) {

	public static CourseSaveNotificationResponse from(NotificationResponse notification) {
		return new CourseSaveNotificationResponse(
			notification.notificationId(),
			notification.type().name(),
			notification.read(),
			notification.notifiedAt(),
			notification.actorId(),
			notification.actorNickname(),
			notification.targetId(),
			notification.targetTitle());
	}
}
