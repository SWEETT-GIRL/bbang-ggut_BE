package sweetgirl.croissant.dessert.exception;

import sweetgirl.croissant.dessert.code.DessertErrorCode;
import sweetgirl.croissant.global.exception.CustomException;

public class DessertException extends CustomException {

	public DessertException(DessertErrorCode errorCode) {
		super(errorCode);
	}
}
