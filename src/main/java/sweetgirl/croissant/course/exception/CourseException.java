package sweetgirl.croissant.course.exception;

import sweetgirl.croissant.course.code.CourseErrorCode;
import sweetgirl.croissant.global.exception.CustomException;

public class CourseException extends CustomException {

	public CourseException(CourseErrorCode errorCode) {
		super(errorCode);
	}
}
