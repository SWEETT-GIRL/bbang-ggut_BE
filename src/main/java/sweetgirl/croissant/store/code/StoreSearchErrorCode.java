package sweetgirl.croissant.store.code;

import org.springframework.http.HttpStatus;

import lombok.AllArgsConstructor;
import lombok.Getter;
import sweetgirl.croissant.global.code.BaseErrorCode;

@Getter
@AllArgsConstructor
public enum StoreSearchErrorCode implements BaseErrorCode {

	SEARCH_TIMEOUT(HttpStatus.SERVICE_UNAVAILABLE, "SEARCH503_1", "검색이 지연되고 있어요. 잠시 후 다시 시도해 주세요.");

	private final HttpStatus httpStatus;
	private final String code;
	private final String message;
}
