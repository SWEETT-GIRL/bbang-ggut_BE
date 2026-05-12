package sweetgirl.croissant.menu.code;

import org.springframework.http.HttpStatus;

import lombok.AllArgsConstructor;
import lombok.Getter;
import sweetgirl.croissant.global.code.BaseErrorCode;

@Getter
@AllArgsConstructor
public enum MenuErrorCode implements BaseErrorCode {

	MENU_NOT_FOUND(HttpStatus.NOT_FOUND, "MENU404", "메뉴를 찾을 수 없습니다.");

	private final HttpStatus httpStatus;
	private final String code;
	private final String message;
}
