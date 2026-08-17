package sweetgirl.croissant.course.code;

import org.springframework.http.HttpStatus;

import lombok.AllArgsConstructor;
import lombok.Getter;
import sweetgirl.croissant.global.code.BaseErrorCode;

@Getter
@AllArgsConstructor
public enum CourseErrorCode implements BaseErrorCode {

	COURSE_NOT_FOUND(HttpStatus.NOT_FOUND, "COURSE404_1", "코스를 찾을 수 없습니다."),
	COURSE_STORE_NOT_FOUND(HttpStatus.NOT_FOUND, "COURSE404_2", "코스 가게를 찾을 수 없습니다."),
	COURSE_IMAGE_NOT_FOUND(HttpStatus.NOT_FOUND, "COURSE404_3", "코스 이미지를 찾을 수 없습니다."),
	COURSE_FORBIDDEN(HttpStatus.FORBIDDEN, "COURSE403_1", "코스에 대한 권한이 없습니다."),
	COURSE_STORE_ALREADY_EXISTS(HttpStatus.BAD_REQUEST, "COURSE400_1", "이미 코스에 추가된 가게입니다."),
	COURSE_SAVED_ALREADY(HttpStatus.BAD_REQUEST, "COURSE400_2", "이미 찜한 코스입니다."),
	COURSE_SAVED_NOT_FOUND(HttpStatus.NOT_FOUND, "COURSE404_4", "찜한 코스를 찾을 수 없습니다."),
	COURSE_FORK_NOT_PUBLIC(HttpStatus.BAD_REQUEST, "COURSE400_3", "공개 코스만 포크할 수 있습니다."),
	COURSE_FORK_NOT_MODIFIED(HttpStatus.BAD_REQUEST, "COURSE400_4", "원본 코스와 동일한 내용은 저장할 수 없습니다."),
	COURSE_ALREADY_PINNED(HttpStatus.BAD_REQUEST, "COURSE400_5", "이미 핀된 코스입니다."),
	COURSE_NOT_PINNED(HttpStatus.BAD_REQUEST, "COURSE400_6", "핀되지 않은 코스입니다."),
	COURSE_PIN_LIMIT_EXCEEDED(HttpStatus.BAD_REQUEST, "COURSE400_7", "핀은 최대 3개까지 설정할 수 있습니다."),
	COURSE_PIN_ORDER_CONFLICT(HttpStatus.BAD_REQUEST, "COURSE400_8", "해당 순위에 이미 핀된 코스가 있습니다."),
	COURSE_SAVE_SELF(HttpStatus.BAD_REQUEST, "COURSE400_9", "자신의 코스는 찜할 수 없습니다."),
	COURSE_STORE_ORDER_INVALID(HttpStatus.BAD_REQUEST, "COURSE400_10", "코스의 모든 가게를 중복 없이 전달해야 합니다."),
	COURSE_STORE_DAY_INVALID(HttpStatus.BAD_REQUEST, "COURSE400_11", "일차 정보가 올바르지 않습니다."),
	COURSE_DATES_INVALID(HttpStatus.BAD_REQUEST, "COURSE400_12", "여행 날짜가 올바르지 않습니다.");

	private final HttpStatus httpStatus;
	private final String code;
	private final String message;
}
