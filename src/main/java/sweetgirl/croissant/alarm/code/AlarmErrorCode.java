package sweetgirl.croissant.alarm.code;

import org.springframework.http.HttpStatus;

import lombok.AllArgsConstructor;
import lombok.Getter;
import sweetgirl.croissant.global.code.BaseErrorCode;

@Getter
@AllArgsConstructor
public enum AlarmErrorCode implements BaseErrorCode {

	ALARM_NOT_FOUND(HttpStatus.NOT_FOUND, "ALARM404", "알람을 찾을 수 없습니다."),
	ALARM_FORBIDDEN(HttpStatus.FORBIDDEN, "ALARM403", "해당 알람에 접근 권한이 없습니다.");

	private final HttpStatus httpStatus;
	private final String code;
	private final String message;
}
