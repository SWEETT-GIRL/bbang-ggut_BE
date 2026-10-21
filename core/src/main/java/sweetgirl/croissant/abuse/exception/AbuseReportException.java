package sweetgirl.croissant.abuse.exception;

import sweetgirl.croissant.abuse.code.AbuseReportErrorCode;
import sweetgirl.croissant.global.exception.CustomException;

public class AbuseReportException extends CustomException {
	public AbuseReportException(AbuseReportErrorCode errorCode) {
		super(errorCode);
	}
}
