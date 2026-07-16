package sweetgirl.croissant.alarm.code;

import org.springframework.http.HttpStatus;

import lombok.AllArgsConstructor;
import lombok.Getter;
import sweetgirl.croissant.global.code.BaseErrorCode;

@Getter
@AllArgsConstructor
public enum AlarmErrorCode implements BaseErrorCode {

	ALARM_NOT_FOUND(HttpStatus.NOT_FOUND, "ALARM404", "알람을 찾을 수 없습니다."),
	ALARM_FORBIDDEN(HttpStatus.FORBIDDEN, "ALARM403", "해당 알람에 접근 권한이 없습니다."),

	FCM_TOKEN_NOT_FOUND(HttpStatus.NOT_FOUND, "FCM_TOKEN404", "FCM Token을 찾을 수 없습니다."),
	FCM_SEND_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "FCM500", "FCM 푸시 발송에 실패했습니다."),

	GEOFENCE_NOT_SUBSCRIBED(HttpStatus.FORBIDDEN, "GEOFENCE403", "구독하지 않은 가게이거나 반경 밖입니다.");

	private final HttpStatus httpStatus;
	private final String code;
	private final String message;
}
