package sweetgirl.croissant.pat.code;

import org.springframework.http.HttpStatus;

import lombok.AllArgsConstructor;
import lombok.Getter;
import sweetgirl.croissant.global.code.BaseErrorCode;

@Getter
@AllArgsConstructor
public enum PatErrorCode implements BaseErrorCode {
	PAT_SELF(HttpStatus.BAD_REQUEST, "PAT400_SELF", "나 자신에게는 톡을 칠 수 없습니다."),
	PAT_TARGET_NOT_FOUND(HttpStatus.NOT_FOUND, "PAT404_TARGET", "톡을 칠 회원을 찾을 수 없습니다."),
	PAT_BLOCKED(HttpStatus.FORBIDDEN, "PAT403_BLOCKED", "차단 관계에서는 톡을 칠 수 없습니다.");

	private final HttpStatus httpStatus;
	private final String code;
	private final String message;
}
