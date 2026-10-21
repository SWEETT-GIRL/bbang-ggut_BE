package sweetgirl.croissant.member.dto.response;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.member.enums.NotificationRadius;

@Getter
@RequiredArgsConstructor
public class NotificationRadiusResponse {

	// 현재 알림 반경 값 (단위: m)
	private final int radius;

	// enum → 응답 DTO 변환
	public static NotificationRadiusResponse of(NotificationRadius notificationRadius) {
		return new NotificationRadiusResponse(notificationRadius.getValue());
	}
}
