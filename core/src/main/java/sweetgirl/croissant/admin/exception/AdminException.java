package sweetgirl.croissant.admin.exception;

import sweetgirl.croissant.admin.code.AdminErrorCode;
import sweetgirl.croissant.global.exception.CustomException;

public class AdminException extends CustomException {

	public AdminException(AdminErrorCode errorCode) {
		super(errorCode);
	}
}
