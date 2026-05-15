package sweetgirl.croissant.menu.code;

import org.springframework.http.HttpStatus;

import lombok.AllArgsConstructor;
import lombok.Getter;
import sweetgirl.croissant.global.code.BaseErrorCode;

@Getter
@AllArgsConstructor
public enum MenuErrorCode implements BaseErrorCode {

	MENU_NOT_FOUND(HttpStatus.NOT_FOUND, "MENU404", "메뉴를 찾을 수 없습니다."),
	ALREADY_RECOMMENDED(HttpStatus.CONFLICT, "MENU409", "이미 추천한 메뉴입니다."),
	NOT_RECOMMENDED(HttpStatus.NOT_FOUND, "MENU4042", "추천하지 않은 메뉴입니다."),
	MENU_CATEGORY_NOT_FOUND(HttpStatus.NOT_FOUND, "MENU4043", "메뉴 카테고리를 찾을 수 없습니다."),
	MENU_ADD_REPORT_NOT_FOUND(HttpStatus.NOT_FOUND, "MENU4044", "메뉴 추가 요청을 찾을 수 없습니다.");

	private final HttpStatus httpStatus;
	private final String code;
	private final String message;
}
