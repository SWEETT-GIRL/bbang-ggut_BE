package sweetgirl.croissant.personalization.exception;

import sweetgirl.croissant.global.exception.CustomException;
import sweetgirl.croissant.personalization.code.PersonalizationErrorCode;

public class PersonalizationException extends CustomException {

	public PersonalizationException(PersonalizationErrorCode errorCode) {
		super(errorCode);
	}
}
