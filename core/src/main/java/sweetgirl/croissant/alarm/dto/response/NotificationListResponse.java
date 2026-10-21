package sweetgirl.croissant.alarm.dto.response;

import java.util.List;

public record NotificationListResponse(
		List<NotificationResponse> notifications,
		boolean hasNext) {
}
