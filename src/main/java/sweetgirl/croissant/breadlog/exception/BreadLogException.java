package sweetgirl.croissant.breadlog.exception;

import sweetgirl.croissant.breadlog.code.BreadLogErrorCode;
import sweetgirl.croissant.global.exception.CustomException;

public class BreadLogException extends CustomException {

	public BreadLogException(BreadLogErrorCode errorCode) {
		super(errorCode);
	}
}
