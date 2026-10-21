package sweetgirl.croissant.member.exception;

import sweetgirl.croissant.global.exception.CustomException;
import sweetgirl.croissant.member.code.DayErrorCode;

public class DayException extends CustomException {
	public DayException(DayErrorCode errorCode) {
		super(errorCode);
	}
}
