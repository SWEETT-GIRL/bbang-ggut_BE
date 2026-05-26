package sweetgirl.croissant.external.google.exception;

import org.springframework.http.HttpStatus;

import lombok.AllArgsConstructor;
import lombok.Getter;
import sweetgirl.croissant.global.code.BaseErrorCode;

@Getter
@AllArgsConstructor
public enum GooglePlacesErrorCode implements BaseErrorCode {

	FIND_PLACE_ERROR(HttpStatus.BAD_GATEWAY, "GOOGLE502_1", "Google Places 장소 검색 API 호출에 실패했습니다."),
	PLACE_DETAILS_ERROR(HttpStatus.BAD_GATEWAY, "GOOGLE502_2", "Google Place Details API 호출에 실패했습니다.");

	private final HttpStatus httpStatus;
	private final String code;
	private final String message;
}
