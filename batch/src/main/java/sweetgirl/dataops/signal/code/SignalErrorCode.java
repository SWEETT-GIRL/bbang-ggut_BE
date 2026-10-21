package sweetgirl.dataops.signal.code;

import static org.springframework.http.HttpStatus.*;

import org.springframework.http.HttpStatus;

import lombok.AllArgsConstructor;
import lombok.Getter;
import sweetgirl.dataops.global.code.BaseErrorCode;

@Getter
@AllArgsConstructor
public enum SignalErrorCode implements BaseErrorCode {

	SIGNAL_NOT_FOUND(NOT_FOUND, "SIGNAL_NOT_FOUND", "가게 신호이 없습니다 — rebuild 로 먼저 만드세요."),
	SIGNAL_REBUILD_HELD(CONFLICT, "SIGNAL_REBUILD_HELD", "응답 완전성 또는 최신성이 확인되지 않아 신호 재빌드를 보류했습니다."),
	SIGNAL_HASH_FAILED(INTERNAL_SERVER_ERROR, "SIGNAL_HASH_FAILED", "신호 해시 계산에 실패했습니다.");

	private final HttpStatus httpStatus;
	private final String code;
	private final String message;
}
