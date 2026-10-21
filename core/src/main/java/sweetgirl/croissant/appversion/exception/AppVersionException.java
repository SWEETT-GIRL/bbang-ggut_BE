package sweetgirl.croissant.appversion.exception;

import sweetgirl.croissant.appversion.code.AppVersionErrorCode;
import sweetgirl.croissant.global.exception.CustomException;

public class AppVersionException extends CustomException {

	public AppVersionException(AppVersionErrorCode errorCode) {
		super(errorCode);
	}
}
