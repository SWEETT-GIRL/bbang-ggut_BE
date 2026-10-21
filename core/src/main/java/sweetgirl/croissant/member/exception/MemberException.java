package sweetgirl.croissant.member.exception;

import sweetgirl.croissant.global.exception.CustomException;
import sweetgirl.croissant.member.code.MemberErrorCode;

public class MemberException extends CustomException {

	public MemberException(MemberErrorCode errorCode) {
		super(errorCode);
	}
}
