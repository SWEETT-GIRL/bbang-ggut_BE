package sweetgirl.croissant.review.exception;

import sweetgirl.croissant.global.exception.CustomException;
import sweetgirl.croissant.review.code.ReviewErrorCode;

public class ReviewException extends CustomException {
	public ReviewException(ReviewErrorCode errorCode) {
		super(errorCode);
	}
}
