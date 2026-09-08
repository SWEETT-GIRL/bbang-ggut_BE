package sweetgirl.croissant.external.gemini.exception;

import org.springframework.http.HttpStatus;

import lombok.AllArgsConstructor;
import lombok.Getter;
import sweetgirl.croissant.global.code.BaseErrorCode;

@Getter
@AllArgsConstructor
public enum GeminiErrorCode implements BaseErrorCode {
	GENERATE_FAILED(HttpStatus.BAD_GATEWAY, "GEMINI502_1", "Gemini 생성 요청에 실패했습니다."),
	/** 429 — 무료 티어 분당 15요청. 호출자가 쉬었다 재시도할 수 있게 따로 둔다 */
	RATE_LIMITED(HttpStatus.BAD_GATEWAY, "GEMINI502_2", "Gemini 요청 한도를 넘었습니다."),
	/** 503 — 수요 폭주. 실측(2026-09-07)에서 3.8·3.7·3.6-flash 가 연달아 이 상태였다 */
	UNAVAILABLE(HttpStatus.BAD_GATEWAY, "GEMINI502_3", "Gemini 가 일시적으로 응답하지 않습니다."),
	EMPTY_RESPONSE(HttpStatus.BAD_GATEWAY, "GEMINI502_4", "Gemini 응답에 내용이 없습니다."),
	INVALID_JSON(HttpStatus.BAD_GATEWAY, "GEMINI502_5", "Gemini 응답을 JSON 으로 해석할 수 없습니다.");

	private final HttpStatus httpStatus;
	private final String code;
	private final String message;
}
