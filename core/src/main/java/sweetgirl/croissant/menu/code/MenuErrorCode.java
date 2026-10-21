package sweetgirl.croissant.menu.code;

import org.springframework.http.HttpStatus;

import lombok.AllArgsConstructor;
import lombok.Getter;
import sweetgirl.croissant.global.code.BaseErrorCode;

@Getter
@AllArgsConstructor
public enum MenuErrorCode implements BaseErrorCode {

	MENU_NOT_FOUND(HttpStatus.NOT_FOUND, "MENU404", "메뉴를 찾을 수 없습니다."),
	ALREADY_LIKED(HttpStatus.CONFLICT, "MENU409", "이미 좋아요한 메뉴입니다."),
	NOT_LIKED(HttpStatus.NOT_FOUND, "MENU4042", "좋아요하지 않은 메뉴입니다."),
	MENU_CATEGORY_NOT_FOUND(HttpStatus.NOT_FOUND, "MENU4043", "메뉴 카테고리를 찾을 수 없습니다."),
	INVALID_REGION(HttpStatus.BAD_REQUEST, "MENU400", "유효하지 않은 지역입니다."),
	MENU_NOT_OWNER(HttpStatus.FORBIDDEN, "MENU403", "본인이 등록한 메뉴만 종류를 정할 수 있습니다."),
	INVALID_PRICE(HttpStatus.BAD_REQUEST, "MENU4001", "가격은 1원 이상 1,000,000원 이하여야 합니다.");

	private final HttpStatus httpStatus;
	private final String code;
	private final String message;
}
