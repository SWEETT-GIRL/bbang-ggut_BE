package sweetgirl.dataops.global.exception;

import org.springframework.http.HttpStatus;

import lombok.Getter;
import sweetgirl.dataops.global.code.BaseErrorCode;

@Getter
public class CustomException extends RuntimeException {

	private final BaseErrorCode errorCode;

	public CustomException(BaseErrorCode errorCode) {
		this(errorCode, errorCode.getMessage());
	}

	public CustomException(BaseErrorCode errorCode, String message) {
		super(message);
		this.errorCode = errorCode;
	}

	public CustomException(BaseErrorCode errorCode, Throwable cause) {
		this(errorCode, errorCode.getMessage(), cause);
	}

	public CustomException(BaseErrorCode errorCode, String message, Throwable cause) {
		super(message, cause);
		this.errorCode = errorCode;
	}

	public HttpStatus getHttpStatus() {
		return errorCode.getHttpStatus();
	}
}
