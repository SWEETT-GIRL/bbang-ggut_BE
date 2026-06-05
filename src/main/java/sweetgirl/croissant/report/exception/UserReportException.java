package sweetgirl.croissant.report.exception;

import sweetgirl.croissant.global.exception.CustomException;
import sweetgirl.croissant.report.code.UserReportErrorCode;

public class UserReportException extends CustomException {
	public UserReportException(UserReportErrorCode errorCode) {
		super(errorCode);
	}
}
