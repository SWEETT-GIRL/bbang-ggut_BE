package sweetgirl.croissant.profile.exception;

import sweetgirl.croissant.global.exception.CustomException;
import sweetgirl.croissant.profile.code.ProfileErrorCode;

public class ProfileException extends CustomException {

	public ProfileException(ProfileErrorCode errorCode) {
		super(errorCode);
	}
}
