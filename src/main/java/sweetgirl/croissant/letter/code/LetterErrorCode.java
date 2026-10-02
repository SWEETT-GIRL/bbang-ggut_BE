package sweetgirl.croissant.letter.code;

import org.springframework.http.HttpStatus;

import lombok.AllArgsConstructor;
import lombok.Getter;
import sweetgirl.croissant.global.code.BaseErrorCode;

@Getter
@AllArgsConstructor
public enum LetterErrorCode implements BaseErrorCode {

	LETTER_THREAD_NOT_FOUND(HttpStatus.NOT_FOUND, "LETTER404", "편지함을 찾을 수 없습니다."),
	LETTER_BODY_EMPTY(HttpStatus.BAD_REQUEST, "LETTER400", "편지 내용을 입력해주세요."),
	LETTER_INVALID_IMAGE_KEY(HttpStatus.BAD_REQUEST, "LETTER400_IMAGE_KEY", "올바르지 않은 사진입니다."),
	LETTER_INVALID_IMAGE_MIME_TYPE(HttpStatus.BAD_REQUEST, "LETTER400_IMAGE_TYPE", "지원하지 않는 사진 형식입니다."),
	LETTER_TOO_FREQUENT(HttpStatus.TOO_MANY_REQUESTS, "LETTER429", "편지를 너무 자주 보내고 있어요. 잠시 후 다시 시도해주세요."),
	LETTER_RECIPIENT_EMPTY(HttpStatus.BAD_REQUEST, "LETTER400_RECIPIENT", "받는 사람을 한 명 이상 지정해주세요.");

	private final HttpStatus httpStatus;
	private final String code;
	private final String message;
}
