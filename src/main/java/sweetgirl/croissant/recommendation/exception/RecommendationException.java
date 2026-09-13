package sweetgirl.croissant.recommendation.exception;

import sweetgirl.croissant.global.exception.CustomException;
import sweetgirl.croissant.recommendation.code.RecommendationErrorCode;

public class RecommendationException extends CustomException {

	public RecommendationException(RecommendationErrorCode errorCode) {
		super(errorCode);
	}
}
