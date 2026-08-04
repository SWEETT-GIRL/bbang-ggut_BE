package sweetgirl.croissant.badge.code;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;

import org.springframework.http.HttpStatus;

import lombok.AllArgsConstructor;
import lombok.Getter;
import sweetgirl.croissant.global.code.BaseErrorCode;

@Getter
@AllArgsConstructor
public enum BadgeErrorCode implements BaseErrorCode {

	BADGE_NOT_FOUND(NOT_FOUND, "BADGE404_1", "뱃지를 찾을 수 없습니다."),
	MEMBER_BADGE_NOT_FOUND(NOT_FOUND, "BADGE404_2", "획득한 뱃지를 찾을 수 없습니다."),
	BADGE_CODE_CONFLICT(CONFLICT, "BADGE409_1", "이미 사용 중인 뱃지 코드입니다."),
	INVALID_BADGE_CONDITION(BAD_REQUEST, "BADGE400_1", "뱃지 조건과 threshold가 올바르지 않습니다.");

	private final HttpStatus httpStatus;
	private final String code;
	private final String message;
}
