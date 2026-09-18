package sweetgirl.croissant.external.llm.exception;

import org.springframework.http.HttpStatus;

import lombok.AllArgsConstructor;
import lombok.Getter;
import sweetgirl.croissant.global.code.BaseErrorCode;

/**
 * LLM 호출 실패 (#662). 셋 다 502 로 올린다 — 우리 잘못이 아니라 바깥이 못 준 것이고, 호출자(실험 러너)가 조합 하나만 실패로
 * 적고 나머지를 계속 돌리게 하려는 것이다.
 */
@Getter
@AllArgsConstructor
public enum LlmErrorCode implements BaseErrorCode {

	GENERATE_FAILED(HttpStatus.BAD_GATEWAY, "LLM502_1", "LLM 생성 요청에 실패했습니다."),
	RATE_LIMITED(HttpStatus.BAD_GATEWAY, "LLM502_2", "LLM 요청 한도를 넘었습니다."),
	UNAVAILABLE(HttpStatus.BAD_GATEWAY, "LLM502_3", "LLM 이 일시적으로 응답하지 않습니다."),
	EMPTY_RESPONSE(HttpStatus.BAD_GATEWAY, "LLM502_4", "LLM 응답에 내용이 없습니다."),
	INVALID_JSON(HttpStatus.BAD_GATEWAY, "LLM502_5", "LLM 응답을 JSON 으로 해석할 수 없습니다."),
	/** Anthropic 의 안전 분류기가 거부한 경우 — HTTP 200 에 stop_reason=refusal 로 온다 */
	REFUSED(HttpStatus.BAD_GATEWAY, "LLM502_6", "LLM 이 요청을 거부했습니다."),

	PROVIDER_NOT_CONFIGURED(HttpStatus.BAD_REQUEST, "LLM400_1", "그 LLM 벤더의 키가 설정되지 않았습니다.");

	private final HttpStatus httpStatus;
	private final String code;
	private final String message;
}
