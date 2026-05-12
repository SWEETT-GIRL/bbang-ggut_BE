package sweetgirl.croissant.store.code;

import org.springframework.http.HttpStatus;

import lombok.AllArgsConstructor;
import lombok.Getter;
import sweetgirl.croissant.global.code.BaseErrorCode;

@Getter
@AllArgsConstructor
public enum StoreErrorCode implements BaseErrorCode {

	STORE_NOT_FOUND(HttpStatus.NOT_FOUND, "STORE404", "빵집을 찾을 수 없습니다."),
	ALREADY_SUBSCRIBED(HttpStatus.CONFLICT, "STORE409_1", "이미 구독 중인 빵집입니다."),
	NOT_SUBSCRIBED(HttpStatus.BAD_REQUEST, "STORE400_1", "구독 중이지 않은 빵집입니다.");

	private final HttpStatus httpStatus;
	private final String code;
	private final String message;
}
