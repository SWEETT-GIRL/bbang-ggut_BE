package sweetgirl.croissant.store.exception;

import sweetgirl.croissant.global.exception.CustomException;
import sweetgirl.croissant.store.code.StoreReportErrorCode;

public class StoreReportException extends CustomException {

	public StoreReportException(StoreReportErrorCode errorCode) {
		super(errorCode);
	}
}
