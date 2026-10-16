package sweetgirl.croissant.external.naver.exception;

import org.springframework.http.HttpStatus;

import lombok.AllArgsConstructor;
import lombok.Getter;
import sweetgirl.croissant.global.code.BaseErrorCode;

@Getter
@AllArgsConstructor
public enum NaverSearchErrorCode implements BaseErrorCode {
	CONFIG_MISSING(HttpStatus.INTERNAL_SERVER_ERROR, "NAVER500_1", "네이버 검색 API 키가 설정되지 않았습니다."),
	SEARCH_FAILED(HttpStatus.BAD_GATEWAY, "NAVER502_1", "네이버 검색 요청에 실패했습니다."),
	/** 429 — 검색 API 는 하루 25,000건. 호출자가 쉬었다 다시 할 수 있게 따로 둔다 */
	RATE_LIMITED(HttpStatus.BAD_GATEWAY, "NAVER502_2", "네이버 검색 요청 한도를 넘었습니다.");

	private final HttpStatus httpStatus;
	private final String code;
	private final String message;
}
