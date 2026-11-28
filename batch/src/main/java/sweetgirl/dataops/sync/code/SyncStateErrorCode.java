package sweetgirl.dataops.sync.code;

import static org.springframework.http.HttpStatus.*;

import org.springframework.http.HttpStatus;

import lombok.AllArgsConstructor;
import lombok.Getter;
import sweetgirl.dataops.global.code.BaseErrorCode;

@Getter
@AllArgsConstructor
public enum SyncStateErrorCode implements BaseErrorCode {

	SYNC_STATE_NOT_FOUND(NOT_FOUND, "SYNC_STATE_NOT_FOUND", "동기화 상태를 찾을 수 없습니다."),
	INVALID_DUE_SELECTION(BAD_REQUEST, "INVALID_DUE_SELECTION", "대상 가게 ID는 양수 1~50개, 조회 한도는 1~50이어야 합니다."),
	SYNC_STATE_TRANSITION_NOT_ALLOWED(
			BAD_REQUEST, "SYNC_STATE_TRANSITION_NOT_ALLOWED", "허용되지 않는 동기화 상태 전이입니다.");

	private final HttpStatus httpStatus;
	private final String code;
	private final String message;
}
