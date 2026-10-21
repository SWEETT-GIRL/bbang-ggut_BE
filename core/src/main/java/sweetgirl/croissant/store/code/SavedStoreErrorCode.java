package sweetgirl.croissant.store.code;

import org.springframework.http.HttpStatus;

import lombok.AllArgsConstructor;
import lombok.Getter;
import sweetgirl.croissant.global.code.BaseErrorCode;

@Getter
@AllArgsConstructor
public enum SavedStoreErrorCode implements BaseErrorCode {

	SAVED_STORE_ALREADY(HttpStatus.BAD_REQUEST, "SAVED_STORE400_1", "이미 찜한 가게입니다."),
	SAVED_STORE_NOT_FOUND(HttpStatus.NOT_FOUND, "SAVED_STORE404_1", "찜한 가게를 찾을 수 없습니다.");

	private final HttpStatus httpStatus;
	private final String code;
	private final String message;
}
