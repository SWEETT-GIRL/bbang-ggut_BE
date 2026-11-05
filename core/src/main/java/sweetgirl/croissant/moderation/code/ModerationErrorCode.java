package sweetgirl.croissant.moderation.code;

import org.springframework.http.HttpStatus;

import lombok.AllArgsConstructor;
import lombok.Getter;
import sweetgirl.croissant.global.code.BaseErrorCode;

@Getter
@AllArgsConstructor
public enum ModerationErrorCode implements BaseErrorCode {

	FORBIDDEN_WORD_DUPLICATE(HttpStatus.CONFLICT, "MODERATION409_WORD", "이미 있는 금칙어입니다."),
	FORBIDDEN_WORD_NOT_FOUND(HttpStatus.NOT_FOUND, "MODERATION404_WORD", "없는 금칙어입니다.");

	private final HttpStatus httpStatus;
	private final String code;
	private final String message;
}
