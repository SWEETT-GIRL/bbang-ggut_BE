package sweetgirl.croissant.comment.exception;

import sweetgirl.croissant.comment.code.CommentErrorCode;
import sweetgirl.croissant.global.exception.CustomException;

public class CommentException extends CustomException {

	public CommentException(CommentErrorCode errorCode) {
		super(errorCode);
	}
}
