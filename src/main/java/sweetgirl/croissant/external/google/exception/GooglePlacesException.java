package sweetgirl.croissant.external.google.exception;

import lombok.Getter;

@Getter
public class GooglePlacesException extends RuntimeException {

	private final GooglePlacesErrorCode errorCode;

	public GooglePlacesException(GooglePlacesErrorCode errorCode, Throwable cause) {
		super(errorCode.getMessage(), cause);
		this.errorCode = errorCode;
	}
}
