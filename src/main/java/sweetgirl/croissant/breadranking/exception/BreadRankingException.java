package sweetgirl.croissant.breadranking.exception;

import sweetgirl.croissant.breadranking.code.BreadRankingErrorCode;
import sweetgirl.croissant.global.exception.CustomException;

public class BreadRankingException extends CustomException {

	public BreadRankingException(BreadRankingErrorCode errorCode) {
		super(errorCode);
	}
}
