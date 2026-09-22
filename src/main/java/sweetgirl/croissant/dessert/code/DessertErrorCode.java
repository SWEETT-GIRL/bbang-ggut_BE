package sweetgirl.croissant.dessert.code;

import org.springframework.http.HttpStatus;

import lombok.AllArgsConstructor;
import lombok.Getter;
import sweetgirl.croissant.global.code.BaseErrorCode;

@Getter
@AllArgsConstructor
public enum DessertErrorCode implements BaseErrorCode {

	DESSERT_NOT_FOUND(HttpStatus.NOT_FOUND, "DESSERT404", "디저트를 찾을 수 없습니다."),
	DESSERT_BRAND_MISSING(HttpStatus.INTERNAL_SERVER_ERROR, "DESSERT500", "편의점 디저트에 브랜드가 없습니다.");

	private final HttpStatus httpStatus;
	private final String code;
	private final String message;
}
