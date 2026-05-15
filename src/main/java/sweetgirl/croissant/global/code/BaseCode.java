package sweetgirl.croissant.global.code;

import org.springframework.http.HttpStatus;

public interface BaseCode {
	HttpStatus getHttpStatus();

	String getCode();

	String getMessage();
}
