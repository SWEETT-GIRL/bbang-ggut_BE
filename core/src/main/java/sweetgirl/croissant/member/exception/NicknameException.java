package sweetgirl.croissant.member.exception;

import sweetgirl.croissant.global.exception.CustomException;
import sweetgirl.croissant.member.code.NicknameErrorCode;

public class NicknameException extends CustomException {

	public NicknameException(NicknameErrorCode errorCode) {
		super(errorCode);
	}

	public NicknameException(NicknameErrorCode errorCode, Throwable cause) {
		super(errorCode, cause);
	}
}
