package sweetgirl.croissant.store.exception;

import sweetgirl.croissant.global.exception.CustomException;
import sweetgirl.croissant.store.code.StoreSearchErrorCode;

public class StoreSearchException extends CustomException {

	public StoreSearchException(StoreSearchErrorCode errorCode) {
		super(errorCode);
	}
}
