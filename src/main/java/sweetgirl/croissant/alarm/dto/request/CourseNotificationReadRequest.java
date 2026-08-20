package sweetgirl.croissant.alarm.dto.request;

import java.util.List;

import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 읽음 처리. 앱은 알림함을 열 때 body 없이 보내 전체를 읽음으로 만든다.
 * notificationIds를 주면 그것만 처리한다 — 알림 종류가 늘면 필요해질 자리라 열어둔다.
 */
@Getter
@NoArgsConstructor
public class CourseNotificationReadRequest {

	private List<Long> notificationIds;

	public boolean isAll() {
		return notificationIds == null || notificationIds.isEmpty();
	}
}
