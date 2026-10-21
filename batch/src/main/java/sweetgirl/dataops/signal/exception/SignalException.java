package sweetgirl.dataops.signal.exception;

import sweetgirl.dataops.global.code.BaseErrorCode;
import sweetgirl.dataops.global.exception.CustomException;

public class SignalException extends CustomException {

	public SignalException(BaseErrorCode errorCode) {
		super(errorCode);
	}

	public SignalException(BaseErrorCode errorCode, String message) {
		super(errorCode, message);
	}
}
