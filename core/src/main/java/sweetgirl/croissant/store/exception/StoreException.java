package sweetgirl.croissant.store.exception;

import sweetgirl.croissant.global.exception.CustomException;
import sweetgirl.croissant.store.code.StoreErrorCode;

public class StoreException extends CustomException {

	public StoreException(StoreErrorCode errorCode) {
		super(errorCode);
	}
}
