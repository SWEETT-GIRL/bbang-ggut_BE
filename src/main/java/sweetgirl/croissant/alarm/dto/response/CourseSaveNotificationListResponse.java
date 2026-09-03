package sweetgirl.croissant.alarm.dto.response;

import java.util.List;

/** 구버전 앱용 목록. 저장 알림만 담는다. */
public record CourseSaveNotificationListResponse(
		List<CourseSaveNotificationResponse> notifications,
		boolean hasNext) {

	public static CourseSaveNotificationListResponse from(NotificationListResponse source) {
		return new CourseSaveNotificationListResponse(
			source.notifications().stream().map(CourseSaveNotificationResponse::from).toList(),
			source.hasNext());
	}
}
