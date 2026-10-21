package sweetgirl.croissant.store.exception;

import sweetgirl.croissant.global.exception.CustomException;
import sweetgirl.croissant.store.code.SavedStoreErrorCode;

public class SavedStoreException extends CustomException {

	public SavedStoreException(SavedStoreErrorCode errorCode) {
		super(errorCode);
	}
}
