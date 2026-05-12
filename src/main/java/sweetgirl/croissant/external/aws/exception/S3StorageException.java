package sweetgirl.croissant.external.aws.exception;

import sweetgirl.croissant.global.code.BaseErrorCode;
import sweetgirl.croissant.global.exception.CustomException;

public class S3StorageException extends CustomException {
	public S3StorageException(BaseErrorCode errorCode) {
		super(errorCode);
	}

	public S3StorageException(BaseErrorCode errorCode, String message) {
		super(errorCode, message);
	}
}
