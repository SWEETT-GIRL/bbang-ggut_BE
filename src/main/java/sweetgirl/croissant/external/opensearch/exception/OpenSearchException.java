package sweetgirl.croissant.external.opensearch.exception;

import sweetgirl.croissant.global.exception.CustomException;

public class OpenSearchException extends CustomException {

	public OpenSearchException(OpenSearchErrorCode errorCode) {
		super(errorCode);
	}

	public OpenSearchException(OpenSearchErrorCode errorCode, Throwable cause) {
		super(errorCode, cause);
	}
}
