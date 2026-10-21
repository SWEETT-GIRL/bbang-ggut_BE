package sweetgirl.croissant.reward.exception;

import sweetgirl.croissant.global.exception.CustomException;
import sweetgirl.croissant.reward.code.RewardErrorCode;

public class RewardException extends CustomException {

	public RewardException(RewardErrorCode errorCode) {
		super(errorCode);
	}
}
