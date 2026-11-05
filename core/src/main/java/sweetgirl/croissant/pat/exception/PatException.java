package sweetgirl.croissant.pat.exception;

import sweetgirl.croissant.global.exception.CustomException;
import sweetgirl.croissant.pat.code.PatErrorCode;

public class PatException extends CustomException {
	public PatException(PatErrorCode errorCode) {
		super(errorCode);
	}
}
