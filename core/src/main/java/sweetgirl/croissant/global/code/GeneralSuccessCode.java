package sweetgirl.croissant.global.code;

import org.springframework.http.HttpStatus;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum GeneralSuccessCode implements BaseCode {

	_OK(HttpStatus.OK, "COMMON200", "요청에 성공했습니다."),
	_CREATED(HttpStatus.CREATED, "COMMON201", "리소스가 생성되었습니다.");

	private final HttpStatus httpStatus;
	private final String code;
	private final String message;
}
