package sweetgirl.dataops.sync.exception;

import sweetgirl.dataops.global.code.BaseErrorCode;
import sweetgirl.dataops.global.exception.CustomException;

public class SyncStateException extends CustomException {

	public SyncStateException(BaseErrorCode errorCode) {
		super(errorCode);
	}

	public SyncStateException(BaseErrorCode errorCode, String message) {
		super(errorCode, message);
	}
}
