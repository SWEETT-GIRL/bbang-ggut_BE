package sweetgirl.croissant.member.dto.response;

import java.time.format.DateTimeFormatter;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.member.entity.NotificationSetting;

@Getter
@RequiredArgsConstructor
public class NotificationTimeResponse {

	private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

	// 알림 시작 시간 (HH:mm 형식)
	private final String startTime;

	// 알림 종료 시간 (HH:mm 형식)
	private final String endTime;

	// NotificationSetting → 응답 DTO 변환
	public static NotificationTimeResponse of(NotificationSetting setting) {
		return new NotificationTimeResponse(
			setting.getStartTime().format(FORMATTER),
			setting.getEndTime().format(FORMATTER));
	}
}
