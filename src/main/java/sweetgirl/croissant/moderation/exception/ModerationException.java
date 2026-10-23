package sweetgirl.croissant.moderation.exception;

import sweetgirl.croissant.global.exception.CustomException;
import sweetgirl.croissant.moderation.code.ModerationErrorCode;

public class ModerationException extends CustomException {

	public ModerationException(ModerationErrorCode errorCode) {
		super(errorCode);
	}
}
