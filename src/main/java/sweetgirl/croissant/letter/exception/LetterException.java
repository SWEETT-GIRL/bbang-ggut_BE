package sweetgirl.croissant.letter.exception;

import sweetgirl.croissant.global.exception.CustomException;
import sweetgirl.croissant.letter.code.LetterErrorCode;

public class LetterException extends CustomException {

	public LetterException(LetterErrorCode errorCode) {
		super(errorCode);
	}
}
