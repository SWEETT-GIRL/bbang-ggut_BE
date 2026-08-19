package sweetgirl.croissant.alarm.dto.response;

import java.util.List;

public record CourseSaveNotificationListResponse(
		List<CourseSaveNotificationResponse> notifications,
		boolean hasNext) {
}
