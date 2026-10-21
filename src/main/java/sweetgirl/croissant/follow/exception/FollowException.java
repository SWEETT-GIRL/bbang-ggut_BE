package sweetgirl.croissant.follow.exception;

import sweetgirl.croissant.follow.code.FollowErrorCode;
import sweetgirl.croissant.global.exception.CustomException;

public class FollowException extends CustomException {

	public FollowException(FollowErrorCode errorCode) {
		super(errorCode);
	}
}
