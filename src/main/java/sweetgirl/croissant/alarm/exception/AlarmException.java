package sweetgirl.croissant.alarm.exception;

import sweetgirl.croissant.alarm.code.AlarmErrorCode;
import sweetgirl.croissant.global.exception.CustomException;

public class AlarmException extends CustomException {

	public AlarmException(AlarmErrorCode errorCode) {
		super(errorCode);
	}
}
