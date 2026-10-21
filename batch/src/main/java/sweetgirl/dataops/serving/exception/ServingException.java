package sweetgirl.dataops.serving.exception;

import sweetgirl.dataops.global.code.BaseErrorCode;
import sweetgirl.dataops.global.exception.CustomException;

public class ServingException extends CustomException {

	public ServingException(BaseErrorCode errorCode) {
		super(errorCode);
	}

	public ServingException(BaseErrorCode errorCode, String message) {
		super(errorCode, message);
	}
}
