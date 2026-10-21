package sweetgirl.dataops.snapshot.exception;

import sweetgirl.dataops.global.code.BaseErrorCode;
import sweetgirl.dataops.global.exception.CustomException;

public class SnapshotException extends CustomException {

	public SnapshotException(BaseErrorCode errorCode) {
		super(errorCode);
	}

	public SnapshotException(BaseErrorCode errorCode, String message) {
		super(errorCode, message);
	}
}
