package sweetgirl.croissant.badge.exception;

import sweetgirl.croissant.badge.code.BadgeErrorCode;
import sweetgirl.croissant.global.exception.CustomException;

public class BadgeException extends CustomException {

	public BadgeException(BadgeErrorCode errorCode) {
		super(errorCode);
	}
}
