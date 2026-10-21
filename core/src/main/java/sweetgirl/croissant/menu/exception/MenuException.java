package sweetgirl.croissant.menu.exception;

import sweetgirl.croissant.global.exception.CustomException;
import sweetgirl.croissant.menu.code.MenuErrorCode;

public class MenuException extends CustomException {
	public MenuException(MenuErrorCode errorCode) {
		super(errorCode);
	}
}
