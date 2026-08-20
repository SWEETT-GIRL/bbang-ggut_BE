package sweetgirl.croissant.alarm.dto.response;

import java.time.LocalDateTime;

import sweetgirl.croissant.alarm.entity.CourseSaveNotification;

/**
 * 알림 한 줄. "{actorNickname} 님이 '{courseTitle}'를 찜했어요" 형태로 앱이 조립한다 —
 * 문구를 서버가 박아 내리면 앱에서 못 고친다.
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

	private static final String TYPE_COURSE_SAVED = "COURSE_SAVED";
	/** 탈퇴한 사람의 찜도 일어난 일이다 — 줄을 없애는 대신 이름만 가린다. */
	private static final String WITHDRAWN_NICKNAME = "알 수 없음";

	public static CourseSaveNotificationResponse from(CourseSaveNotification notification) {
		boolean withdrawn = notification.getActor().isDeleted();
		return new CourseSaveNotificationResponse(
			notification.getId(),
			TYPE_COURSE_SAVED,
			notification.isRead(),
			notification.getNotifiedAt(),
			withdrawn ? null : notification.getActor().getId(),
			withdrawn ? WITHDRAWN_NICKNAME : notification.getActor().getNickname(),
			notification.getCourse().getId(),
			notification.getCourse().getTitle());
	}
}
